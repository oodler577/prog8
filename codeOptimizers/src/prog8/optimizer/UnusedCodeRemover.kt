package prog8.optimizer

import prog8.ast.*
import prog8.ast.base.VarDeclType
import prog8.ast.expressions.BinaryExpression
import prog8.ast.expressions.FunctionCall
import prog8.ast.expressions.PrefixExpression
import prog8.ast.expressions.TypecastExpression
import prog8.ast.statements.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.compilerinterface.CallGraph
import prog8.compilerinterface.ICompilationTarget
import prog8.compilerinterface.IErrorReporter
import prog8.compilerinterface.isInRegularRAMof


class UnusedCodeRemover(private val program: Program,
                        private val errors: IErrorReporter,
                        private val compTarget: ICompilationTarget
): AstWalker() {

    private val callgraph = CallGraph(program)

    override fun before(module: Module, parent: Node): Iterable<IAstModification> {
        return if (!module.isLibrary && (module.containsNoCodeNorVars || callgraph.unused(module)))
            listOf(IAstModification.Remove(module, parent as IStatementContainer))
        else
            noModifications
    }

    override fun before(breakStmt: Break, parent: Node): Iterable<IAstModification> {
        reportUnreachable(breakStmt, parent as IStatementContainer)
        return emptyList()
    }

    override fun before(jump: Jump, parent: Node): Iterable<IAstModification> {
        reportUnreachable(jump, parent as IStatementContainer)
        return emptyList()
    }

    override fun before(returnStmt: Return, parent: Node): Iterable<IAstModification> {
        reportUnreachable(returnStmt, parent as IStatementContainer)
        return emptyList()
    }

    override fun before(functionCallStatement: FunctionCallStatement, parent: Node): Iterable<IAstModification> {
        if(functionCallStatement.target.nameInSource.last() == "exit")
            reportUnreachable(functionCallStatement, parent as IStatementContainer)
        return emptyList()
    }

    private fun reportUnreachable(stmt: Statement, parent: IStatementContainer) {
        when(val next = stmt.nextSibling()) {
            null, is Label, is Directive, is VarDecl, is InlineAssembly, is Subroutine -> {}
            else -> errors.warn("unreachable code", next.position)
        }
    }

    override fun after(scope: AnonymousScope, parent: Node): Iterable<IAstModification> {
        val removeDoubleAssignments = deduplicateAssignments(scope.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, scope) }
    }

    override fun after(block: Block, parent: Node): Iterable<IAstModification> {
        if("force_output" !in block.options()) {
            if (block.containsNoCodeNorVars) {
                if(block.name != internedStringsModuleName)
                    errors.warn("removing unused block '${block.name}'", block.position)
                return listOf(IAstModification.Remove(block, parent as IStatementContainer))
            }
            if(callgraph.unused(block)) {
                errors.warn("removing unused block '${block.name}'", block.position)
                return listOf(IAstModification.Remove(block, parent as IStatementContainer))
            }
        }

        val removeDoubleAssignments = deduplicateAssignments(block.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, block) }
    }

    override fun after(subroutine: Subroutine, parent: Node): Iterable<IAstModification> {
        val forceOutput = "force_output" in subroutine.definingBlock.options()
        if (subroutine !== program.entrypoint && !forceOutput && !subroutine.inline && !subroutine.isAsmSubroutine) {
            if(callgraph.unused(subroutine)) {
                if(subroutine.containsNoCodeNorVars) {
                    if(!subroutine.definingModule.isLibrary)
                        errors.warn("removing empty subroutine '${subroutine.name}'", subroutine.position)
                    val removals = mutableListOf(IAstModification.Remove(subroutine, parent as IStatementContainer))
                    callgraph.calledBy[subroutine]?.let {
                        for(node in it)
                            removals.add(IAstModification.Remove(node, node.parent as IStatementContainer))
                    }
                    return removals
                }
                if(!subroutine.definingModule.isLibrary)
                    errors.warn("removing unused subroutine '${subroutine.name}'", subroutine.position)
                return listOf(IAstModification.Remove(subroutine, parent as IStatementContainer))
            }
        }

        val removeDoubleAssignments = deduplicateAssignments(subroutine.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, subroutine) }
    }

    override fun after(decl: VarDecl, parent: Node): Iterable<IAstModification> {
        if(decl.type==VarDeclType.VAR) {
            val forceOutput = "force_output" in decl.definingBlock.options()
            if (!forceOutput && !decl.autogeneratedDontRemove && !decl.sharedWithAsm && !decl.definingBlock.isInLibrary) {
                val usages = callgraph.usages(decl)
                if (usages.isEmpty()) {
                    errors.warn("removing unused variable '${decl.name}'", decl.position)
                    return listOf(IAstModification.Remove(decl, parent as IStatementContainer))
                } else {
                    // if all usages are just an assignment to this vardecl
                    // and it is in regular RAM, then remove the var as well including all assignments
                    val assignTargets = usages.mapNotNull {
                        if(it.parent is AssignTarget)
                                it.parent as AssignTarget
                        else if(it.parent.parent is AssignTarget)
                                it.parent.parent as AssignTarget
                        else null
                    }.filter {
                        it.isInRegularRAMof(compTarget.machine)
                    }
                    if(assignTargets.size==usages.size) {
                        // TODO FIX THAT A MEMREAD OF THE VARIABLE ISN'T RECOGNISED AS A USE (imageviewer iff_module.p8 pixptr)
                        errors.warn("removing unused variable '${decl.name}'", decl.position)
                        val assignmentsToRemove = assignTargets.map { it.parent to it.parent.parent as IStatementContainer}.toSet()
                        return assignmentsToRemove.map {
                            IAstModification.Remove(it.first, it.second)
                        } + listOf(
                            IAstModification.Remove(decl, parent as IStatementContainer)
                        )
                    }
                }
            }
        }

        return noModifications
    }

    private fun deduplicateAssignments(statements: List<Statement>): List<Assignment> {
        // removes 'duplicate' assignments that assign the same target directly after another
        val linesToRemove = mutableListOf<Assignment>()

        for (stmtPairs in statements.windowed(2, step = 1)) {
            val assign1 = stmtPairs[0] as? Assignment
            val assign2 = stmtPairs[1] as? Assignment
            if (assign1 != null && assign2 != null && !assign2.isAugmentable) {
                if (assign1.target.isSameAs(assign2.target, program) && assign1.target.isInRegularRAMof(compTarget.machine))  {
                    if(assign2.target.identifier==null || !assign2.value.referencesIdentifier(*(assign2.target.identifier!!.nameInSource.toTypedArray())))
                        // only remove the second assignment if its value is a simple expression!
                        when(assign2.value) {
                            is PrefixExpression,
                            is BinaryExpression,
                            is TypecastExpression,
                            is FunctionCall -> { /* don't remove */ }
                            else -> linesToRemove.add(assign1)
                        }
                }
            }
        }

        return linesToRemove
    }
}