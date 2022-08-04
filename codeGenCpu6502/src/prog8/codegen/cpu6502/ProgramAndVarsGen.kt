package prog8.codegen.cpu6502

import prog8.ast.Program
import prog8.ast.statements.*
import prog8.code.*
import prog8.code.core.*
import prog8.codegen.cpu6502.assignment.AsmAssignTarget
import prog8.codegen.cpu6502.assignment.TargetStorageKind
import prog8.compiler.CallGraph
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.absoluteValue

/**
 * Generates the main parts of the program:
 *  - entry/exit code
 *  - initialization routines
 *  - blocks
 *  - subroutines
 *  - all variables (note: VarDecl ast nodes are *NOT* used anymore for this! now uses IVariablesAndConsts data tables!)
 */
internal class ProgramAndVarsGen(
    val program: Program,
    val options: CompilationOptions,
    val errors: IErrorReporter,
    private val symboltable: SymbolTable,
    private val functioncallAsmGen: FunctionCallAsmGen,
    private val asmgen: AsmGen,
    private val allocator: VariableAllocator,
    private val zeropage: Zeropage
) {
    private val compTarget = options.compTarget
    private val callGraph = CallGraph(program, true)
    private val blockVariableInitializers = program.allBlocks.associateWith { it.statements.filterIsInstance<Assignment>() }

    internal fun generate() {
        val allInitializers = blockVariableInitializers.asSequence().flatMap { it.value }
        require(allInitializers.all { it.origin==AssignmentOrigin.VARINIT }) {"all block-level assignments must be a variable initializer"}

        header()
        val allBlocks = program.allBlocks
        if(allBlocks.first().name != "main")
            throw AssemblyError("first block should be 'main'")

        if(errors.noErrors())  {
            program.allBlocks.forEach { block2asm(it) }

            // the global list of all floating point constants for the whole program
            asmgen.out("; global float constants")
            for (flt in allocator.globalFloatConsts) {
                val floatFill = compTarget.machine.getFloatAsmBytes(flt.key)
                val floatvalue = flt.key
                asmgen.out("${flt.value}\t.byte  $floatFill  ; float $floatvalue")
            }

            memorySlabs()
            footer()
        }
    }

    private fun header() {
        val ourName = this.javaClass.name
        val cpu = when(compTarget.machine.cpu) {
            CpuType.CPU6502 -> "6502"
            CpuType.CPU65c02 -> "w65c02"
            else -> "unsupported"
        }

        asmgen.out("; $cpu assembly code for '${program.name}'")
        asmgen.out("; generated by $ourName on ${LocalDateTime.now().withNano(0)}")
        asmgen.out("; assembler syntax is for the 64tasm cross-assembler")
        asmgen.out("; output options: output=${options.output} launcher=${options.launcher} zp=${options.zeropage}")
        asmgen.out("")
        asmgen.out(".cpu  '$cpu'\n.enc  'none'\n")

        // the global prog8 variables needed
        val zp = zeropage
        asmgen.out("P8ZP_SCRATCH_B1 = ${zp.SCRATCH_B1}")
        asmgen.out("P8ZP_SCRATCH_REG = ${zp.SCRATCH_REG}")
        asmgen.out("P8ZP_SCRATCH_W1 = ${zp.SCRATCH_W1}    ; word")
        asmgen.out("P8ZP_SCRATCH_W2 = ${zp.SCRATCH_W2}    ; word")
        asmgen.out(".weak")   // hack to allow user to override the following two with command line redefinition (however, just use '-esa' command line option instead!)
        asmgen.out("P8ESTACK_LO = ${compTarget.machine.ESTACK_LO.toHex()}")
        asmgen.out("P8ESTACK_HI = ${compTarget.machine.ESTACK_HI.toHex()}")
        asmgen.out(".endweak")

        if(options.symbolDefs.isNotEmpty()) {
            asmgen.out("; -- user supplied symbols on the command line")
            for((name, value) in options.symbolDefs) {
                asmgen.out("$name = $value")
            }
        }

        when(options.output) {
            OutputType.RAW -> {
                asmgen.out("; ---- raw assembler program ----")
                asmgen.out("* = ${options.loadAddress.toHex()}\n")
            }
            OutputType.PRG -> {
                when(options.launcher) {
                    CbmPrgLauncherType.BASIC -> {
                        if (options.loadAddress != options.compTarget.machine.PROGRAM_LOAD_ADDRESS) {
                            errors.err("BASIC output must have load address ${options.compTarget.machine.PROGRAM_LOAD_ADDRESS.toHex()}", program.toplevelModule.position)
                        }
                        asmgen.out("; ---- basic program with sys call ----")
                        asmgen.out("* = ${options.loadAddress.toHex()}")
                        val year = LocalDate.now().year
                        asmgen.out("  .word  (+), $year")
                        asmgen.out("  .null  $9e, format(' %d ', prog8_entrypoint), $3a, $8f, ' prog8'")
                        asmgen.out("+\t.word  0")
                        asmgen.out("prog8_entrypoint\t; assembly code starts here\n")
                        if(!options.noSysInit)
                            asmgen.out("  jsr  ${compTarget.name}.init_system")
                        asmgen.out("  jsr  ${compTarget.name}.init_system_phase2")
                    }
                    CbmPrgLauncherType.NONE -> {
                        asmgen.out("; ---- program without basic sys call ----")
                        asmgen.out("* = ${options.loadAddress.toHex()}\n")
                        if(!options.noSysInit)
                            asmgen.out("  jsr  ${compTarget.name}.init_system")
                        asmgen.out("  jsr  ${compTarget.name}.init_system_phase2")
                    }
                }
            }
            OutputType.XEX -> {
                asmgen.out("; ---- atari xex program ----")
                asmgen.out("* = ${options.loadAddress.toHex()}\n")
                if(!options.noSysInit)
                    asmgen.out("  jsr  ${compTarget.name}.init_system")
                asmgen.out("  jsr  ${compTarget.name}.init_system_phase2")
            }
        }

        if(options.zeropage !in arrayOf(ZeropageType.BASICSAFE, ZeropageType.DONTUSE)) {
            asmgen.out("""
                ; zeropage is clobbered so we need to reset the machine at exit
                lda  #>sys.reset_system
                pha
                lda  #<sys.reset_system
                pha""")
        }

        // make sure that on the cx16 and c64, basic rom is banked in again when we exit the program
        when(compTarget.name) {
            "cx16" -> {
                if(options.floats)
                    asmgen.out("  lda  #4 |  sta  $01")    // to use floats, make sure Basic rom is banked in
                asmgen.out("  jsr  main.start")
                asmgen.out("  jmp  ${compTarget.name}.cleanup_at_exit")
            }
            "c64" -> {
                asmgen.out("  jsr  main.start |  lda  #31 |  sta  $01")
                if(!options.noSysInit)
                    asmgen.out("  jmp  ${compTarget.name}.cleanup_at_exit")
                else
                    asmgen.out("  rts")
            }
            "c128" -> {
                asmgen.out("  jsr  main.start")
                // TODO c128: how to bank basic+kernal back in?
                if(!options.noSysInit)
                    asmgen.out("  jmp  ${compTarget.name}.cleanup_at_exit")
                else
                    asmgen.out("  rts")
            }
            else -> asmgen.jmp("main.start")
        }
    }

    private fun memorySlabs() {
        asmgen.out("; memory slabs")
        asmgen.out("prog8_slabs\t.block")
        for((name, info) in allocator.memorySlabs) {
            if(info.second>1u)
                asmgen.out("\t.align  ${info.second.toHex()}")
            asmgen.out("$name\t.fill  ${info.first}")
        }
        asmgen.out("\t.bend")
    }

    private fun footer() {
        // program end
        asmgen.out("prog8_program_end\t; end of program label for progend()")
    }

    private fun block2asm(block: Block) {
        asmgen.out("")
        asmgen.out("; ---- block: '${block.name}' ----")
        if(block.address!=null)
            asmgen.out("* = ${block.address!!.toHex()}")
        else {
            if("align_word" in block.options())
                asmgen.out("\t.align 2")
            else if("align_page" in block.options())
                asmgen.out("\t.align $100")
        }

        asmgen.out("${block.name}\t" + (if("force_output" in block.options()) ".block\n" else ".proc\n"))
        asmgen.outputSourceLine(block)

        createBlockVariables(block)
        asmsubs2asm(block.statements)

        asmgen.out("")
        asmgen.out("; subroutines in this block")

        // First translate regular statements, and then put the subroutines at the end.
        // (regular statements = everything except the initialization assignments;
        // these will be part of the prog8_init_vars init routine generated below)
        val initializers = blockVariableInitializers.getValue(block)
        val statements = block.statements.filterNot { it in initializers }
        val (subroutine, stmts) = statements.partition { it is Subroutine }
        stmts.forEach { asmgen.translate(it) }
        subroutine.forEach { asmgen.translate(it) }

        if(!options.dontReinitGlobals) {
            // generate subroutine to initialize block-level (global) variables
            if (initializers.isNotEmpty()) {
                asmgen.out("prog8_init_vars\t.proc\n")
                initializers.forEach { assign -> asmgen.translate(assign) }
                asmgen.out("  rts\n  .pend")
            }
        }

        asmgen.out(if("force_output" in block.options()) "\n\t.bend\n" else "\n\t.pend\n")
    }

    private fun getVars(scope: StNode): Map<String, StNode> =
        scope.children.filter { it.value.type in arrayOf(StNodeType.STATICVAR, StNodeType.CONSTANT, StNodeType.MEMVAR) }

    private fun createBlockVariables(block: Block) {
        val scope = symboltable.lookupOrElse(block.name) { throw AssemblyError("lookup") }
        require(scope.type==StNodeType.BLOCK)
        val varsInBlock = getVars(scope)

        // Zeropage Variables
        val varnames = varsInBlock.filter { it.value.type==StNodeType.STATICVAR }.map { it.value.scopedName }.toSet()
        zeropagevars2asm(varnames)

        // MemDefs and Consts
        val mvs = varsInBlock
            .filter { it.value.type==StNodeType.MEMVAR }
            .map { it.value as StMemVar }
        val consts = varsInBlock
            .filter { it.value.type==StNodeType.CONSTANT }
            .map { it.value as StConstant }
        memdefsAndConsts2asm(mvs, consts)

        // normal statically allocated variables
        val variables = varsInBlock
            .filter { it.value.type==StNodeType.STATICVAR && !allocator.isZpVar(it.value.scopedName) }
            .map { it.value as StStaticVariable }
        nonZpVariables2asm(variables)
    }

    internal fun translateSubroutine(sub: Subroutine) {
        var onlyVariables = false

        if(sub.inline) {
            if(options.optimize) {
                if(sub.isAsmSubroutine || callGraph.unused(sub))
                    return

                // from an inlined subroutine only the local variables are generated,
                // all other code statements are omitted in the subroutine itself
                // (they've been inlined at the call site, remember?)
                onlyVariables = true
            }
        }

        asmgen.out("")

        if(sub.isAsmSubroutine) {
            if(sub.asmAddress!=null)
                return  // already done at the memvars section

            // asmsub with most likely just an inline asm in it
            asmgen.out("${sub.name}\t.proc")
            sub.statements.forEach { asmgen.translate(it) }
            asmgen.out("  .pend\n")
        } else {
            // regular subroutine
            asmgen.out("${sub.name}\t.proc")

            val scope = symboltable.lookupOrElse(sub.scopedName) { throw AssemblyError("lookup") }
            require(scope.type==StNodeType.SUBROUTINE)
            val varsInSubroutine = getVars(scope)

            // Zeropage Variables
            val varnames = varsInSubroutine.filter { it.value.type==StNodeType.STATICVAR }.map { it.value.scopedName }.toSet()
            zeropagevars2asm(varnames)

            // MemDefs and Consts
            val mvs = varsInSubroutine
                .filter { it.value.type==StNodeType.MEMVAR }
                .map { it.value as StMemVar }
            val consts = varsInSubroutine
                .filter { it.value.type==StNodeType.CONSTANT }
                .map { it.value as StConstant }
            memdefsAndConsts2asm(mvs, consts)

            asmsubs2asm(sub.statements)

            // the main.start subroutine is the program's entrypoint and should perform some initialization logic
            if(sub.name=="start" && sub.definingBlock.name=="main")
                entrypointInitialization()

            if(functioncallAsmGen.optimizeIntArgsViaRegisters(sub)) {
                asmgen.out("; simple int arg(s) passed via register(s)")
                if(sub.parameters.size==1) {
                    val dt = sub.parameters[0].type
                    val target = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, dt, sub, variableAsmName = sub.parameters[0].name)
                    if(dt in ByteDatatypes)
                        asmgen.assignRegister(RegisterOrPair.A, target)
                    else
                        asmgen.assignRegister(RegisterOrPair.AY, target)
                } else {
                    require(sub.parameters.size==2)
                    // 2 simple byte args, first in A, second in Y
                    val target1 = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, sub.parameters[0].type, sub, variableAsmName = sub.parameters[0].name)
                    val target2 = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, sub.parameters[1].type, sub, variableAsmName = sub.parameters[1].name)
                    asmgen.assignRegister(RegisterOrPair.A, target1)
                    asmgen.assignRegister(RegisterOrPair.Y, target2)
                }
            }

            if(!onlyVariables) {
                asmgen.out("; statements")
                sub.statements.forEach { asmgen.translate(it) }
            }

            asmgen.out("; variables")
            val asmGenInfo = asmgen.subroutineExtra(sub)
            for((dt, name, addr) in asmGenInfo.extraVars) {
                if(addr!=null)
                    asmgen.out("$name = $addr")
                else when(dt) {
                    DataType.UBYTE -> asmgen.out("$name    .byte  0")
                    DataType.UWORD -> asmgen.out("$name    .word  0")
                    else -> throw AssemblyError("weird dt")
                }
            }
            if(asmGenInfo.usedRegsaveA)      // will probably never occur
                asmgen.out("prog8_regsaveA     .byte  0")
            if(asmGenInfo.usedRegsaveX)
                asmgen.out("prog8_regsaveX     .byte  0")
            if(asmGenInfo.usedRegsaveY)
                asmgen.out("prog8_regsaveY     .byte  0")
            if(asmGenInfo.usedFloatEvalResultVar1)
                asmgen.out("$subroutineFloatEvalResultVar1    .byte  0,0,0,0,0")
            if(asmGenInfo.usedFloatEvalResultVar2)
                asmgen.out("$subroutineFloatEvalResultVar2    .byte  0,0,0,0,0")

            // normal statically allocated variables
            val variables = varsInSubroutine
                .filter { it.value.type==StNodeType.STATICVAR && !allocator.isZpVar(it.value.scopedName) }
                .map { it.value as StStaticVariable }
            nonZpVariables2asm(variables)

            asmgen.out("  .pend\n")
        }
    }

    private fun entrypointInitialization() {
        asmgen.out("; program startup initialization")
        asmgen.out("  cld")
        if(!options.dontReinitGlobals) {
            blockVariableInitializers.forEach {
                if (it.value.isNotEmpty())
                    asmgen.out("  jsr  ${it.key.name}.prog8_init_vars")
            }
        }

        // string and array variables in zeropage that have initializer value, should be initialized
        val stringVarsWithInitInZp = getZpStringVarsWithInitvalue()
        val arrayVarsWithInitInZp = getZpArrayVarsWithInitvalue()
        if(stringVarsWithInitInZp.isNotEmpty() || arrayVarsWithInitInZp.isNotEmpty()) {
            asmgen.out("; zp str and array initializations")
            stringVarsWithInitInZp.forEach {
                val name = asmgen.asmVariableName(it.name)
                asmgen.out("""
                    lda  #<${name}
                    ldy  #>${name}
                    sta  P8ZP_SCRATCH_W1
                    sty  P8ZP_SCRATCH_W1+1
                    lda  #<${name}_init_value
                    ldy  #>${name}_init_value
                    jsr  prog8_lib.strcpy""")
            }
            arrayVarsWithInitInZp.forEach {
                val size = it.alloc.size
                val name = asmgen.asmVariableName(it.name)
                asmgen.out("""
                    lda  #<${name}_init_value
                    ldy  #>${name}_init_value
                    sta  cx16.r0L
                    sty  cx16.r0H
                    lda  #<${name}
                    ldy  #>${name}
                    sta  cx16.r1L
                    sty  cx16.r1H
                    lda  #<$size
                    ldy  #>$size
                    jsr  sys.memcopy""")
            }
            asmgen.out("  jmp  +")
        }

        stringVarsWithInitInZp.forEach {
            val varname = asmgen.asmVariableName(it.name)+"_init_value"
            outputStringvar(varname, it.value.second, it.value.first)
        }

        arrayVarsWithInitInZp.forEach {
            val varname = asmgen.asmVariableName(it.name)+"_init_value"
            arrayVariable2asm(varname, it.alloc.dt, it.value, null)
        }

        asmgen.out("""+       tsx
                    stx  prog8_lib.orig_stackpointer    ; required for sys.exit()                    
                    ldx  #255       ; init estack ptr
                    clv
                    clc""")
    }

    private class ZpStringWithInitial(
        val name: List<String>,
        val alloc: Zeropage.ZpAllocation,
        val value: Pair<String, Encoding>
    )

    private class ZpArrayWithInitial(
        val name: List<String>,
        val alloc: Zeropage.ZpAllocation,
        val value: StArray
    )

    private fun getZpStringVarsWithInitvalue(): Collection<ZpStringWithInitial> {
        val result = mutableListOf<ZpStringWithInitial>()
        val vars = allocator.zeropageVars.filter { it.value.dt==DataType.STR }
        for (variable in vars) {
            val svar = symboltable.lookup(variable.key) as StStaticVariable        // TODO faster in flat lookup table
            if(svar.initialStringValue!=null)
                result.add(ZpStringWithInitial(variable.key, variable.value, svar.initialStringValue!!))
        }
        return result
    }

    private fun getZpArrayVarsWithInitvalue(): Collection<ZpArrayWithInitial> {
        val result = mutableListOf<ZpArrayWithInitial>()
        val vars = allocator.zeropageVars.filter { it.value.dt in ArrayDatatypes }
        for (variable in vars) {
            val svar = symboltable.lookup(variable.key) as StStaticVariable        // TODO faster in flat lookup table
            if(svar.initialArrayValue!=null)
                result.add(ZpArrayWithInitial(variable.key, variable.value, svar.initialArrayValue!!))
        }
        return result
    }

    private fun zeropagevars2asm(varNames: Set<List<String>>) {
        val zpVariables = allocator.zeropageVars.filter { it.key in varNames }
        for ((scopedName, zpvar) in zpVariables) {
            if (scopedName.size == 2 && scopedName[0] == "cx16" && scopedName[1][0] == 'r' && scopedName[1][1].isDigit())
                continue        // The 16 virtual registers of the cx16 are not actual variables in zp, they're memory mapped
            asmgen.out("${scopedName.last()} \t= ${zpvar.address} \t; zp ${zpvar.dt}")
        }
    }

    private fun nonZpVariables2asm(variables: List<StStaticVariable>) {
        asmgen.out("")
        asmgen.out("; non-zeropage variables")
        val (stringvars, othervars) = variables.partition { it.dt==DataType.STR }
        stringvars.forEach {
            outputStringvar(it.name, it.initialStringValue!!.second, it.initialStringValue!!.first)
        }
        othervars.sortedBy { it.type }.forEach {
            staticVariable2asm(it)
        }
    }

    private fun staticVariable2asm(variable: StStaticVariable) {
        val name = variable.name
        val initialValue: Number =
            if(variable.initialNumericValue!=null) {
                if(variable.dt== DataType.FLOAT)
                    variable.initialNumericValue!!
                else
                    variable.initialNumericValue!!.toInt()
            } else 0

        when (variable.dt) {
            DataType.UBYTE -> asmgen.out("$name\t.byte  ${initialValue.toHex()}")
            DataType.BYTE -> asmgen.out("$name\t.char  $initialValue")
            DataType.UWORD -> asmgen.out("$name\t.word  ${initialValue.toHex()}")
            DataType.WORD -> asmgen.out("$name\t.sint  $initialValue")
            DataType.FLOAT -> {
                if(initialValue==0) {
                    asmgen.out("$name\t.byte  0,0,0,0,0  ; float")
                } else {
                    val floatFill = compTarget.machine.getFloatAsmBytes(initialValue)
                    asmgen.out("$name\t.byte  $floatFill  ; float $initialValue")
                }
            }
            DataType.STR -> {
                throw AssemblyError("all string vars should have been interned into prog")
            }
            in ArrayDatatypes -> arrayVariable2asm(name, variable.dt, variable.initialArrayValue, variable.length)
            else -> {
                throw AssemblyError("weird dt")
            }
        }
    }

    private fun arrayVariable2asm(varname: String, dt: DataType, value: StArray?, orNumberOfZeros: Int?) {
        when(dt) {
            DataType.ARRAY_UB -> {
                val data = makeArrayFillDataUnsigned(dt, value, orNumberOfZeros)
                if (data.size <= 16)
                    asmgen.out("$varname\t.byte  ${data.joinToString()}")
                else {
                    asmgen.out(varname)
                    for (chunk in data.chunked(16))
                        asmgen.out("  .byte  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_B -> {
                val data = makeArrayFillDataSigned(dt, value, orNumberOfZeros)
                if (data.size <= 16)
                    asmgen.out("$varname\t.char  ${data.joinToString()}")
                else {
                    asmgen.out(varname)
                    for (chunk in data.chunked(16))
                        asmgen.out("  .char  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_UW -> {
                val data = makeArrayFillDataUnsigned(dt, value, orNumberOfZeros)
                if (data.size <= 16)
                    asmgen.out("$varname\t.word  ${data.joinToString()}")
                else {
                    asmgen.out(varname)
                    for (chunk in data.chunked(16))
                        asmgen.out("  .word  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_W -> {
                val data = makeArrayFillDataSigned(dt, value, orNumberOfZeros)
                if (data.size <= 16)
                    asmgen.out("$varname\t.sint  ${data.joinToString()}")
                else {
                    asmgen.out(varname)
                    for (chunk in data.chunked(16))
                        asmgen.out("  .sint  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_F -> {
                val array = value ?: zeroFilledArray(orNumberOfZeros!!)
                val floatFills = array.map {
                    compTarget.machine.getFloatAsmBytes(it.number!!)
                }
                asmgen.out(varname)
                for (f in array.zip(floatFills))
                    asmgen.out("  .byte  ${f.second}  ; float ${f.first}")
            }
            else -> throw AssemblyError("require array dt")
        }
    }

    private fun zeroFilledArray(numElts: Int): StArray {
        val values = mutableListOf<StArrayElement>()
        repeat(numElts) {
            values.add(StArrayElement(0.0, null))
        }
        return values
    }

    private fun memdefsAndConsts2asm(memvars: Collection<StMemVar>, consts: Collection<StConstant>) {
        memvars.forEach {
            asmgen.out("  ${it.name} = ${it.address.toHex()}")
        }
        consts.forEach {
            if(it.dt==DataType.FLOAT)
                asmgen.out("  ${it.name} = ${it.value}")
            else
                asmgen.out("  ${it.name} = ${it.value.toHex()}")
        }
    }

    private fun asmsubs2asm(statements: List<Statement>) {
        statements
            .filter { it is Subroutine && it.isAsmSubroutine && it.asmAddress!=null }
            .forEach { asmsub ->
                asmsub as Subroutine
                asmgen.out("  ${asmsub.name} = ${asmsub.asmAddress!!.toHex()}")
            }
    }

    private fun outputStringvar(varname: String, encoding: Encoding, value: String) {
        asmgen.out("$varname\t; $encoding:\"${value.escape().replace("\u0000", "<NULL>")}\"", false)
        val bytes = compTarget.encodeString(value, encoding).plus(0.toUByte())
        val outputBytes = bytes.map { "$" + it.toString(16).padStart(2, '0') }
        for (chunk in outputBytes.chunked(16))
            asmgen.out("  .byte  " + chunk.joinToString())
    }

    private fun makeArrayFillDataUnsigned(dt: DataType, value: StArray?, orNumberOfZeros: Int?): List<String> {
        val array = value ?: zeroFilledArray(orNumberOfZeros!!)
        return when (dt) {
            DataType.ARRAY_UB ->
                // byte array can never contain pointer-to types, so treat values as all integers
                array.map {
                    val number = it.number!!.toInt()
                    "$"+number.toString(16).padStart(2, '0')
                }
            DataType.ARRAY_UW -> array.map {
                if(it.number!=null) {
                    "$" + it.number!!.toInt().toString(16).padStart(4, '0')
                }
                else if(it.addressOf!=null) {
                    asmgen.asmSymbolName(it.addressOf!!)
                }
                else
                    throw AssemblyError("weird array elt")
            }
            else -> throw AssemblyError("invalid dt")
        }
    }

    private fun makeArrayFillDataSigned(dt: DataType, value: StArray?, orNumberOfZeros: Int?): List<String> {
        val array = value ?: zeroFilledArray(orNumberOfZeros!!)
        return when (dt) {
            // byte array can never contain pointer-to types, so treat values as all integers
            DataType.ARRAY_UB ->
                array.map {
                    val number = it.number!!.toInt()
                    "$"+number.toString(16).padStart(2, '0')
                }
            DataType.ARRAY_B ->
                array.map {
                    val number = it.number!!.toInt()
                    val hexnum = number.absoluteValue.toString(16).padStart(2, '0')
                    if(number>=0)
                        "$$hexnum"
                    else
                        "-$$hexnum"
                }
            DataType.ARRAY_UW -> array.map {
                val number = it.number!!.toInt()
                "$" + number.toString(16).padStart(4, '0')
            }
            DataType.ARRAY_W -> array.map {
                val number = it.number!!.toInt()
                val hexnum = number.absoluteValue.toString(16).padStart(4, '0')
                if(number>=0)
                    "$$hexnum"
                else
                    "-$$hexnum"
            }
            else -> throw AssemblyError("invalid dt")
        }
    }

}
