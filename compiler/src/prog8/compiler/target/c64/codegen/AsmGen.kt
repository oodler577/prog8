package prog8.compiler.target.c64.codegen

import prog8.ast.Node
import prog8.ast.Program
import prog8.ast.antlr.escape
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.compiler.*
import prog8.compiler.target.c64.AssemblyProgram
import prog8.compiler.target.c64.MachineDefinition
import prog8.compiler.target.c64.MachineDefinition.ESTACK_HI_HEX
import prog8.compiler.target.c64.MachineDefinition.ESTACK_LO_HEX
import prog8.compiler.target.c64.Petscii
import prog8.functions.BuiltinFunctions
import prog8.functions.FunctionSignature
import java.math.RoundingMode
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.ArrayDeque
import kotlin.math.absoluteValue


internal class AssemblyError(msg: String) : RuntimeException(msg)


internal class AsmGen(val program: Program,
                      val options: CompilationOptions,
                      val zeropage: Zeropage,
                      val outputDir: Path) {

    private val assemblyLines = mutableListOf<String>()
    private val globalFloatConsts = mutableMapOf<Double, String>()     // all float values in the entire program (value -> varname)
    private val allocatedZeropageVariables = mutableMapOf<String, Pair<Int, DataType>>()
    private val breakpointLabels = mutableListOf<String>()
    private val builtinFunctionsAsmGen = BuiltinFunctionsAsmGen(program, this)
    private val forloopsAsmGen = ForLoopsAsmGen(program, this)
    private val postincrdecrAsmGen = PostIncrDecrAsmGen(program, this)
    private val functioncallAsmGen = FunctionCallAsmGen(program, this)
    private val assignmentAsmGen = AssignmentAsmGen(program, this)
    private val expressionsAsmGen = ExpressionsAsmGen(program, this)
    internal val loopEndLabels = ArrayDeque<String>()
    internal val loopContinueLabels = ArrayDeque<String>()

    internal fun compileToAssembly(optimize: Boolean): AssemblyProgram {
        assemblyLines.clear()
        loopEndLabels.clear()
        loopContinueLabels.clear()

        println("Generating assembly code... ")

        header()
        val allBlocks = program.allBlocks()
        if(allBlocks.first().name != "main")
            throw AssemblyError("first block should be 'main'")
        for(b in program.allBlocks())
            block2asm(b)
        footer()

        if(optimize) {
            var optimizationsDone = 1
            while (optimizationsDone > 0) {
                optimizationsDone = optimizeAssembly(assemblyLines)
            }
        }

        val outputFile = outputDir.resolve("${program.name}.asm").toFile()
        outputFile.printWriter().use {
            for (line in assemblyLines) { it.println(line) }
        }

        return AssemblyProgram(program.name, outputDir)
    }

    private fun header() {
        val ourName = this.javaClass.name
        out("; 6502 assembly code for '${program.name}'")
        out("; generated by $ourName on ${LocalDateTime.now().withNano(0)}")
        out("; assembler syntax is for the 64tasm cross-assembler")
        out("; output options: output=${options.output} launcher=${options.launcher} zp=${options.zeropage}")
        out("\n.cpu  '6502'\n.enc  'none'\n")

        program.actualLoadAddress = program.definedLoadAddress
        if (program.actualLoadAddress == 0)   // fix load address
            program.actualLoadAddress = if (options.launcher == LauncherType.BASIC)
                MachineDefinition.BASIC_LOAD_ADDRESS else MachineDefinition.RAW_LOAD_ADDRESS

        when {
            options.launcher == LauncherType.BASIC -> {
                if (program.actualLoadAddress != 0x0801)
                    throw AssemblyError("BASIC output must have load address $0801")
                out("; ---- basic program with sys call ----")
                out("* = ${program.actualLoadAddress.toHex()}")
                val year = LocalDate.now().year
                out("  .word  (+), $year")
                out("  .null  $9e, format(' %d ', _prog8_entrypoint), $3a, $8f, ' prog8 by idj'")
                out("+\t.word  0")
                out("_prog8_entrypoint\t; assembly code starts here\n")
                out("  jsr  prog8_lib.init_system")
            }
            options.output == OutputType.PRG -> {
                out("; ---- program without basic sys call ----")
                out("* = ${program.actualLoadAddress.toHex()}\n")
                out("  jsr  prog8_lib.init_system")
            }
            options.output == OutputType.RAW -> {
                out("; ---- raw assembler program ----")
                out("* = ${program.actualLoadAddress.toHex()}\n")
            }
        }

        if (zeropage.exitProgramStrategy != Zeropage.ExitProgramStrategy.CLEAN_EXIT) {
            // disable shift-commodore charset switching and run/stop key
            out("  lda  #$80")
            out("  lda  #$80")
            out("  sta  657\t; disable charset switching")
            out("  lda  #239")
            out("  sta  808\t; disable run/stop key")
        }

        out("  ldx  #\$ff\t; init estack pointer")

        out("  ; initialize the variables in each block")
        for (block in program.allBlocks()) {
            val initVarsSub = block.statements.singleOrNull { it is Subroutine && it.name == initvarsSubName }
            if(initVarsSub!=null)
                out("  jsr  ${block.name}.$initvarsSubName")
        }

        out("  clc")
        when (zeropage.exitProgramStrategy) {
            Zeropage.ExitProgramStrategy.CLEAN_EXIT -> {
                out("  jmp  main.start\t; jump to program entrypoint")
            }
            Zeropage.ExitProgramStrategy.SYSTEM_RESET -> {
                out("  jsr  main.start\t; call program entrypoint")
                out("  jmp  (c64.RESET_VEC)\t; cold reset")
            }
        }
        out("")
    }

    private fun footer() {
        // the global list of all floating point constants for the whole program
        out("; global float constants")
        for (flt in globalFloatConsts) {
            val mflpt5 = MachineDefinition.Mflpt5.fromNumber(flt.key)
            val floatFill = makeFloatFill(mflpt5)
            val floatvalue = flt.key
            out("${flt.value}\t.byte  $floatFill  ; float $floatvalue")
        }
    }

    private fun block2asm(block: Block) {
        out("\n\n; ---- block: '${block.name}' ----")
        out("${block.name}\t" + (if("force_output" in block.options()) ".block\n" else ".proc\n"))

        if(block.address!=null) {
            out(".cerror * > ${block.address.toHex()}, 'block address overlaps by ', *-${block.address.toHex()},' bytes'")
            out("* = ${block.address.toHex()}")
        }

        outputSourceLine(block)
        zeropagevars2asm(block.statements)
        memdefs2asm(block.statements)
        vardecls2asm(block.statements)
        out("\n; subroutines in this block")

        // first translate regular statements, and then put the subroutines at the end.
        val (subroutine, stmts) = block.statements.partition { it is Subroutine }
        stmts.forEach { translate(it) }
        subroutine.forEach { translateSubroutine(it as Subroutine) }

        out(if("force_output" in block.options()) "\n\t.bend\n" else "\n\t.pend\n")
    }

    private var generatedLabelSequenceNumber: Int = 0

    internal fun makeLabel(postfix: String): String {
        generatedLabelSequenceNumber++
        return "_prog8_label_${generatedLabelSequenceNumber}_$postfix"
    }

    private fun outputSourceLine(node: Node) {
        out(" ;\tsrc line: ${node.position.file}:${node.position.line}")
    }

    internal fun out(str: String, splitlines: Boolean = true) {
        val fragment = (if(" | " in str) str.replace("|", "\n") else str).trim('\n')

        if (splitlines) {
            for (line in fragment.split('\n')) {
                val trimmed = if (line.startsWith(' ')) "\t" + line.trim() else line.trim()
                // trimmed = trimmed.replace(Regex("^\\+\\s+"), "+\t")  // sanitize local label indentation
                assemblyLines.add(trimmed)
            }
        } else assemblyLines.add(fragment)
    }

    private fun makeFloatFill(flt: MachineDefinition.Mflpt5): String {
        val b0 = "$" + flt.b0.toString(16).padStart(2, '0')
        val b1 = "$" + flt.b1.toString(16).padStart(2, '0')
        val b2 = "$" + flt.b2.toString(16).padStart(2, '0')
        val b3 = "$" + flt.b3.toString(16).padStart(2, '0')
        val b4 = "$" + flt.b4.toString(16).padStart(2, '0')
        return "$b0, $b1, $b2, $b3, $b4"
    }

    private fun encodeStr(str: String, dt: DataType): List<Short> {
        return when(dt) {
            DataType.STR -> {
                val bytes = Petscii.encodePetscii(str, true)
                bytes.plus(0)
            }
            DataType.STR_S -> {
                val bytes = Petscii.encodeScreencode(str, true)
                bytes.plus(0)
            }
            else -> throw AssemblyError("invalid str type")
        }
    }

    private fun zeropagevars2asm(statements: List<Statement>) {
        out("; vars allocated on zeropage")
        val variables = statements.filterIsInstance<VarDecl>().filter { it.type==VarDeclType.VAR }
        for(variable in variables) {
            // should NOT allocate subroutine parameters on the zero page
            val fullName = variable.scopedname
            val zpVar = allocatedZeropageVariables[fullName]
            if(zpVar==null) {
                // This var is not on the ZP yet. Attempt to move it there (if it's not a float, those take up too much space)
                if(variable.zeropage != ZeropageWish.NOT_IN_ZEROPAGE &&
                        variable.datatype in zeropage.allowedDatatypes
                        && variable.datatype != DataType.FLOAT
                        && options.zeropage != ZeropageType.DONTUSE) {
                    try {
                        val address = zeropage.allocate(fullName, variable.datatype, null)
                        out("${variable.name} = $address\t; auto zp ${variable.datatype}")
                        // make sure we add the var to the set of zpvars for this block
                        allocatedZeropageVariables[fullName] = Pair(address, variable.datatype)
                    } catch (x: ZeropageDepletedError) {
                        // leave it as it is.
                    }
                }
            }
        }
    }

    private fun vardecl2asm(decl: VarDecl) {
        when (decl.datatype) {
            DataType.UBYTE -> out("${decl.name}\t.byte  0")
            DataType.BYTE -> out("${decl.name}\t.char  0")
            DataType.UWORD -> out("${decl.name}\t.word  0")
            DataType.WORD -> out("${decl.name}\t.sint  0")
            DataType.FLOAT -> out("${decl.name}\t.byte  0,0,0,0,0  ; float")
            DataType.STRUCT -> {}       // is flattened
            DataType.STR, DataType.STR_S -> {
                val string = (decl.value as StringLiteralValue).value
                val encoded = encodeStr(string, decl.datatype)
                outputStringvar(decl, encoded)
            }
            DataType.ARRAY_UB -> {
                val data = makeArrayFillDataUnsigned(decl)
                if (data.size <= 16)
                    out("${decl.name}\t.byte  ${data.joinToString()}")
                else {
                    out(decl.name)
                    for (chunk in data.chunked(16))
                        out("  .byte  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_B -> {
                val data = makeArrayFillDataSigned(decl)
                if (data.size <= 16)
                    out("${decl.name}\t.char  ${data.joinToString()}")
                else {
                    out(decl.name)
                    for (chunk in data.chunked(16))
                        out("  .char  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_UW -> {
                val data = makeArrayFillDataUnsigned(decl)
                if (data.size <= 16)
                    out("${decl.name}\t.word  ${data.joinToString()}")
                else {
                    out(decl.name)
                    for (chunk in data.chunked(16))
                        out("  .word  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_W -> {
                val data = makeArrayFillDataSigned(decl)
                if (data.size <= 16)
                    out("${decl.name}\t.sint  ${data.joinToString()}")
                else {
                    out(decl.name)
                    for (chunk in data.chunked(16))
                        out("  .sint  " + chunk.joinToString())
                }
            }
            DataType.ARRAY_F -> {
                val array = (decl.value as ArrayLiteralValue).value
                val floatFills = array.map {
                    val number = (it as NumericLiteralValue).number
                    makeFloatFill(MachineDefinition.Mflpt5.fromNumber(number))
                }
                out(decl.name)
                for (f in array.zip(floatFills))
                    out("  .byte  ${f.second}  ; float ${f.first}")
            }
        }
    }

    private fun memdefs2asm(statements: List<Statement>) {
        out("\n; memdefs and kernel subroutines")
        val memvars = statements.filterIsInstance<VarDecl>().filter { it.type==VarDeclType.MEMORY || it.type==VarDeclType.CONST }
        for(m in memvars) {
            out("  ${m.name} = ${(m.value as NumericLiteralValue).number.toHex()}")
        }
        val asmSubs = statements.filterIsInstance<Subroutine>().filter { it.isAsmSubroutine }
        for(sub in asmSubs) {
            if(sub.asmAddress!=null) {
                if(sub.statements.isNotEmpty())
                    throw AssemblyError("kernel subroutine cannot have statements")
                out("  ${sub.name} = ${sub.asmAddress.toHex()}")
            }
        }
    }

    private fun vardecls2asm(statements: List<Statement>) {
        out("\n; non-zeropage variables")
        val vars = statements.filterIsInstance<VarDecl>().filter { it.type==VarDeclType.VAR }

        // first output the flattened struct member variables *in order*
        // after that, the other variables sorted by their datatype

        val (structMembers, normalVars) = vars.partition { it.struct!=null }
        structMembers.forEach { vardecl2asm(it) }

        // special treatment for string types: merge strings that are identical
        val encodedstringVars = normalVars
                .filter {it.datatype in StringDatatypes }
                .map { it to encodeStr((it.value as StringLiteralValue).value, it.datatype) }
                .groupBy({it.second}, {it.first})
        for((encoded, variables) in encodedstringVars) {
            variables.dropLast(1).forEach { out(it.name) }
            val lastvar = variables.last()
            outputStringvar(lastvar, encoded)
        }

        // non-string variables
        normalVars.filter{ it.datatype !in StringDatatypes}.sortedBy { it.datatype }.forEach {
            if(it.scopedname !in allocatedZeropageVariables)
                vardecl2asm(it)
        }
    }

    private fun outputStringvar(lastvar: VarDecl, encoded: List<Short>) {
        val string = (lastvar.value as StringLiteralValue).value
        out("${lastvar.name}\t; ${lastvar.datatype} \"${escape(string).replace("\u0000", "<NULL>")}\"")
        val outputBytes = encoded.map { "$" + it.toString(16).padStart(2, '0') }
        for (chunk in outputBytes.chunked(16))
            out("  .byte  " + chunk.joinToString())
    }

    private fun makeArrayFillDataUnsigned(decl: VarDecl): List<String> {
        val array = (decl.value as ArrayLiteralValue).value
        return when {
            decl.datatype == DataType.ARRAY_UB ->
                // byte array can never contain pointer-to types, so treat values as all integers
                array.map {
                    val number = (it as NumericLiteralValue).number.toInt()
                    "$"+number.toString(16).padStart(2, '0')
                }
            decl.datatype== DataType.ARRAY_UW -> array.map {
                if(it is NumericLiteralValue) {
                    "$" + it.number.toInt().toString(16).padStart(4, '0')
                } else {
                    (it as AddressOf).identifier.nameInSource.joinToString(".")
                }
            }
            else -> throw AssemblyError("invalid arraysize type")
        }
    }

    private fun makeArrayFillDataSigned(decl: VarDecl): List<String> {
        val array = (decl.value as ArrayLiteralValue).value

        return when {
            decl.datatype == DataType.ARRAY_UB ->
                // byte array can never contain pointer-to types, so treat values as all integers
                array.map {
                    val number = (it as NumericLiteralValue).number.toInt()
                    val hexnum = number.toString(16).padStart(2, '0')
                    "$$hexnum"
                }
            decl.datatype == DataType.ARRAY_B ->
                // byte array can never contain pointer-to types, so treat values as all integers
                array.map {
                    val number = (it as NumericLiteralValue).number.toInt()
                    val hexnum = number.absoluteValue.toString(16).padStart(2, '0')
                    if(number>=0)
                        "$$hexnum"
                    else
                        "-$$hexnum"
                }
            decl.datatype== DataType.ARRAY_UW -> array.map {
                val number = (it as NumericLiteralValue).number.toInt()
                val hexnum = number.toString(16).padStart(4, '0')
                "$$hexnum"
            }
            decl.datatype== DataType.ARRAY_W -> array.map {
                val number = (it as NumericLiteralValue).number.toInt()
                val hexnum = number.absoluteValue.toString(16).padStart(4, '0')
                if(number>=0)
                    "$$hexnum"
                else
                    "-$$hexnum"
            }
            else -> throw AssemblyError("invalid arraysize type ${decl.datatype}")
        }
    }

    internal fun getFloatConst(number: Double): String {
        // try to match the ROM float constants to save memory
        val mflpt5 = MachineDefinition.Mflpt5.fromNumber(number)
        val floatbytes = shortArrayOf(mflpt5.b0, mflpt5.b1, mflpt5.b2, mflpt5.b3, mflpt5.b4)
        when {
            floatbytes.contentEquals(shortArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)) -> return "c64flt.FL_ZERO"
            floatbytes.contentEquals(shortArrayOf(0x82, 0x49, 0x0f, 0xda, 0xa1)) -> return "c64flt.FL_PIVAL"
            floatbytes.contentEquals(shortArrayOf(0x90, 0x80, 0x00, 0x00, 0x00)) -> return "c64flt.FL_N32768"
            floatbytes.contentEquals(shortArrayOf(0x81, 0x00, 0x00, 0x00, 0x00)) -> return "c64flt.FL_FONE"
            floatbytes.contentEquals(shortArrayOf(0x80, 0x35, 0x04, 0xf3, 0x34)) -> return "c64flt.FL_SQRHLF"
            floatbytes.contentEquals(shortArrayOf(0x81, 0x35, 0x04, 0xf3, 0x34)) -> return "c64flt.FL_SQRTWO"
            floatbytes.contentEquals(shortArrayOf(0x80, 0x80, 0x00, 0x00, 0x00)) -> return "c64flt.FL_NEGHLF"
            floatbytes.contentEquals(shortArrayOf(0x80, 0x31, 0x72, 0x17, 0xf8)) -> return "c64flt.FL_LOG2"
            floatbytes.contentEquals(shortArrayOf(0x84, 0x20, 0x00, 0x00, 0x00)) -> return "c64flt.FL_TENC"
            floatbytes.contentEquals(shortArrayOf(0x9e, 0x6e, 0x6b, 0x28, 0x00)) -> return "c64flt.FL_NZMIL"
            floatbytes.contentEquals(shortArrayOf(0x80, 0x00, 0x00, 0x00, 0x00)) -> return "c64flt.FL_FHALF"
            floatbytes.contentEquals(shortArrayOf(0x81, 0x38, 0xaa, 0x3b, 0x29)) -> return "c64flt.FL_LOGEB2"
            floatbytes.contentEquals(shortArrayOf(0x81, 0x49, 0x0f, 0xda, 0xa2)) -> return "c64flt.FL_PIHALF"
            floatbytes.contentEquals(shortArrayOf(0x83, 0x49, 0x0f, 0xda, 0xa2)) -> return "c64flt.FL_TWOPI"
            floatbytes.contentEquals(shortArrayOf(0x7f, 0x00, 0x00, 0x00, 0x00)) -> return "c64flt.FL_FR4"
            else -> {
                // attempt to correct for a few rounding issues
                when (number.toBigDecimal().setScale(10, RoundingMode.HALF_DOWN).toDouble()) {
                    3.1415926536 -> return "c64flt.FL_PIVAL"
                    1.4142135624 -> return "c64flt.FL_SQRTWO"
                    0.7071067812 -> return "c64flt.FL_SQRHLF"
                    0.6931471806 -> return "c64flt.FL_LOG2"
                    else -> {}
                }

                // no ROM float const for this value, create our own
                val name = globalFloatConsts[number]
                if(name!=null)
                    return name
                val newName = "prog8_float_const_${globalFloatConsts.size}"
                globalFloatConsts[number] = newName
                return newName
            }
        }
    }

    internal fun signExtendAtoMsb(destination: String) =
            """
        ora  #$7f
        bmi  +
        lda  #0
+       sta  $destination
        """

    internal fun asmIdentifierName(identifier: IdentifierReference): String {
        val name = if(identifier.memberOfStruct(program.namespace)!=null) {
            identifier.targetVarDecl(program.namespace)!!.name
        } else {
            identifier.nameInSource.joinToString(".")
        }
        return fixNameSymbols(name)
    }

    internal fun fixNameSymbols(name: String) = name.replace("<", "prog8_").replace(">", "")     // take care of the autogenerated invalid (anon) label names

    private fun branchInstruction(condition: BranchCondition, complement: Boolean) =
            if(complement) {
                when (condition) {
                    BranchCondition.CS -> "bcc"
                    BranchCondition.CC -> "bcs"
                    BranchCondition.EQ, BranchCondition.Z -> "beq"
                    BranchCondition.NE, BranchCondition.NZ -> "bne"
                    BranchCondition.VS -> "bvc"
                    BranchCondition.VC -> "bvs"
                    BranchCondition.MI, BranchCondition.NEG -> "bmi"
                    BranchCondition.PL, BranchCondition.POS -> "bpl"
                }
            } else {
                when (condition) {
                    BranchCondition.CS -> "bcs"
                    BranchCondition.CC -> "bcc"
                    BranchCondition.EQ, BranchCondition.Z -> "beq"
                    BranchCondition.NE, BranchCondition.NZ -> "bne"
                    BranchCondition.VS -> "bvs"
                    BranchCondition.VC -> "bvc"
                    BranchCondition.MI, BranchCondition.NEG -> "bmi"
                    BranchCondition.PL, BranchCondition.POS -> "bpl"
                }
            }

    internal fun readAndPushArrayvalueWithIndexA(arrayDt: DataType, variable: IdentifierReference) {
        val variablename = asmIdentifierName(variable)
        when (arrayDt) {
            DataType.STR, DataType.STR_S, DataType.ARRAY_UB, DataType.ARRAY_B ->
                out("  tay |  lda  $variablename,y |  sta  $ESTACK_LO_HEX,x |  dex")
            DataType.ARRAY_UW, DataType.ARRAY_W ->
                out("  asl  a |  tay |  lda  $variablename,y |  sta  $ESTACK_LO_HEX,x |  lda  $variablename+1,y |  sta  $ESTACK_HI_HEX,x | dex")
            DataType.ARRAY_F ->
                // index * 5 is done in the subroutine that's called
                out("""
                    sta  $ESTACK_LO_HEX,x
                    dex
                    lda  #<$variablename
                    ldy  #>$variablename
                    jsr  c64flt.push_float_from_indexed_var
                """)
            else ->
                throw AssemblyError("weird array type")
        }
    }

    internal fun saveRegister(register: Register) {
        when(register) {
            Register.A -> out("  pha")
            Register.X -> out("  txa | pha")
            Register.Y -> out("  tya | pha")
        }
    }

    internal fun restoreRegister(register: Register) {
        when(register) {
            Register.A -> out("  pla")
            Register.X -> out("  pla | tax")
            Register.Y -> out("  pla | tay")
        }
    }

    private fun translateSubroutine(sub: Subroutine) {
        out("")
        outputSourceLine(sub)

        if(sub.isAsmSubroutine) {
            if(sub.asmAddress!=null)
                return  // already done at the memvars section

            // asmsub with most likely just an inline asm in it
            out("${sub.name}\t.proc")
            sub.statements.forEach{ translate(it) }
            out("  .pend\n")
        } else {
            // regular subroutine
            out("${sub.name}\t.proc")
            zeropagevars2asm(sub.statements)
            memdefs2asm(sub.statements)
            out("; statements")
            sub.statements.forEach{ translate(it) }
            out("; variables")
            vardecls2asm(sub.statements)
            out("  .pend\n")
        }
    }

    internal fun translate(stmt: Statement) {
        outputSourceLine(stmt)
        when(stmt) {
            is VarDecl, is StructDecl, is NopStatement -> {}
            is Directive -> translate(stmt)
            is Return -> translate(stmt)
            is Subroutine -> translateSubroutine(stmt)
            is InlineAssembly -> translate(stmt)
            is FunctionCallStatement -> {
                val functionName = stmt.target.nameInSource.last()
                val builtinFunc = BuiltinFunctions[functionName]
                if(builtinFunc!=null) {
                    builtinFunctionsAsmGen.translateFunctioncallStatement(stmt, builtinFunc)
                } else {
                    functioncallAsmGen.translateFunctionCall(stmt)
                    // discard any results from the stack:
                    val sub = stmt.target.targetSubroutine(program.namespace)!!
                    val returns = sub.returntypes.zip(sub.asmReturnvaluesRegisters)
                    for((t, reg) in returns) {
                        if(reg.stack) {
                            if (t in IntegerDatatypes || t in PassByReferenceDatatypes) out("  inx")
                            else if (t == DataType.FLOAT) out("  inx |  inx |  inx")
                        }
                    }
                }
            }
            is Assignment -> assignmentAsmGen.translate(stmt)
            is Jump -> translate(stmt)
            is PostIncrDecr -> postincrdecrAsmGen.translate(stmt)
            is Label -> translate(stmt)
            is BranchStatement -> translate(stmt)
            is IfStatement -> translate(stmt)
            is ForLoop -> forloopsAsmGen.translate(stmt)
            is Continue -> out("  jmp  ${loopContinueLabels.peek()}")
            is Break -> out("  jmp  ${loopEndLabels.peek()}")
            is WhileLoop -> translate(stmt)
            is RepeatLoop -> translate(stmt)
            is WhenStatement -> translate(stmt)
            is BuiltinFunctionStatementPlaceholder -> throw AssemblyError("builtin function should not have placeholder anymore?")
            is AnonymousScope -> translate(stmt)
            is Block -> throw AssemblyError("block should have been handled elsewhere")
        }
    }

    private fun translate(stmt: IfStatement) {
        expressionsAsmGen.translateExpression(stmt.condition)
        translateTestStack(stmt.condition.inferType(program).typeOrElse(DataType.STRUCT))
        val elseLabel = makeLabel("if_else")
        val endLabel = makeLabel("if_end")
        out("  beq  $elseLabel")
        translate(stmt.truepart)
        out("  jmp  $endLabel")
        out(elseLabel)
        translate(stmt.elsepart)
        out(endLabel)
    }

    private fun translateTestStack(dataType: DataType) {
        when(dataType) {
            in ByteDatatypes -> out("  inx |  lda  $ESTACK_LO_HEX,x")
            in WordDatatypes -> out("  inx |  lda  $ESTACK_LO_HEX,x |  ora  $ESTACK_HI_HEX,x")
            DataType.FLOAT -> throw AssemblyError("conditional value should be an integer (boolean)")
            else -> throw AssemblyError("non-numerical dt")
        }
    }

    private fun translate(stmt: WhileLoop) {
        val whileLabel = makeLabel("while")
        val endLabel = makeLabel("whileend")
        loopEndLabels.push(endLabel)
        loopContinueLabels.push(whileLabel)
        out(whileLabel)
        // TODO optimize for the simple cases, can we avoid stack use?
        expressionsAsmGen.translateExpression(stmt.condition)
        val conditionDt = stmt.condition.inferType(program)
        if(!conditionDt.isKnown)
            throw AssemblyError("unknown condition dt")
        if(conditionDt.typeOrElse(DataType.BYTE) in ByteDatatypes) {
            out("  inx |  lda  $ESTACK_LO_HEX,x  |  beq  $endLabel")
        } else {
            out("""
                inx
                lda  $ESTACK_LO_HEX,x
                bne  +
                lda  $ESTACK_HI_HEX,x
                beq  $endLabel
+  """)
        }
        translate(stmt.body)
        out("  jmp  $whileLabel")
        out(endLabel)
        loopEndLabels.pop()
        loopContinueLabels.pop()
    }

    private fun translate(stmt: RepeatLoop) {
        val repeatLabel = makeLabel("repeat")
        val endLabel = makeLabel("repeatend")
        loopEndLabels.push(endLabel)
        loopContinueLabels.push(repeatLabel)
        out(repeatLabel)
        // TODO optimize this for the simple cases, can we avoid stack use?
        translate(stmt.body)
        expressionsAsmGen.translateExpression(stmt.untilCondition)
        val conditionDt = stmt.untilCondition.inferType(program)
        if(!conditionDt.isKnown)
            throw AssemblyError("unknown condition dt")
        if(conditionDt.typeOrElse(DataType.BYTE) in ByteDatatypes) {
            out("  inx |  lda  $ESTACK_LO_HEX,x  |  beq  $repeatLabel")
        } else {
            out("""
                inx
                lda  $ESTACK_LO_HEX,x
                bne  +
                lda  $ESTACK_HI_HEX,x
                beq  $repeatLabel
+ """)
        }
        out(endLabel)
        loopEndLabels.pop()
        loopContinueLabels.pop()
    }

    private fun translate(stmt: WhenStatement) {
        expressionsAsmGen.translateExpression(stmt.condition)
        val endLabel = makeLabel("choice_end")
        val choiceBlocks = mutableListOf<Pair<String, AnonymousScope>>()
        val conditionDt = stmt.condition.inferType(program)
        if(!conditionDt.isKnown)
            throw AssemblyError("unknown condition dt")
        if(conditionDt.typeOrElse(DataType.BYTE) in ByteDatatypes)
            out("  inx |  lda  $ESTACK_LO_HEX,x")
        else
            out("  inx |  lda  $ESTACK_LO_HEX,x |  ldy  $ESTACK_HI_HEX,x")
        for(choice in stmt.choices) {
            val choiceLabel = makeLabel("choice")
            if(choice.values==null) {
                // the else choice
                translate(choice.statements)
                out("  jmp  $endLabel")
            } else {
                choiceBlocks.add(Pair(choiceLabel, choice.statements))
                for (cv in choice.values!!) {
                    val value = (cv as NumericLiteralValue).number.toInt()
                    if(conditionDt.typeOrElse(DataType.BYTE) in ByteDatatypes) {
                        out("  cmp  #${value.toHex()} |  beq  $choiceLabel")
                    } else {
                        out("""
                            cmp  #<${value.toHex()}
                            bne  +
                            cpy  #>${value.toHex()}
                            beq  $choiceLabel
+                            
                            """)
                    }
                }
            }
        }
        for(choiceBlock in choiceBlocks) {
            out(choiceBlock.first)
            translate(choiceBlock.second)
            out("  jmp  $endLabel")
        }
        out(endLabel)
    }

    private fun translate(stmt: Label) {
        out(stmt.name)
    }

    private fun translate(scope: AnonymousScope) {
        // note: the variables defined in an anonymous scope have been moved to their defining subroutine's scope
        scope.statements.forEach{ translate(it) }
    }

    private fun translate(stmt: BranchStatement) {
        if(stmt.truepart.containsNoCodeNorVars() && stmt.elsepart.containsCodeOrVars())
            throw AssemblyError("only else part contains code, shoud have been switched already")

        val jump = stmt.truepart.statements.first() as? Jump
        if(jump!=null) {
            // branch with only a jump
            val instruction = branchInstruction(stmt.condition, false)
            out("  $instruction  ${getJumpTarget(jump)}")
            translate(stmt.elsepart)
        } else {
            if(stmt.elsepart.containsNoCodeNorVars()) {
                val instruction = branchInstruction(stmt.condition, true)
                val elseLabel = makeLabel("branch_else")
                out("  $instruction  $elseLabel")
                translate(stmt.truepart)
                out(elseLabel)
            } else {
                val instruction = branchInstruction(stmt.condition, false)
                val trueLabel = makeLabel("branch_true")
                val endLabel = makeLabel("branch_end")
                out("  $instruction  $trueLabel")
                translate(stmt.elsepart)
                out("  jmp  $endLabel")
                out(trueLabel)
                translate(stmt.truepart)
                out(endLabel)
            }
        }
    }

    private fun translate(stmt: Directive) {
        when(stmt.directive) {
            "%asminclude" -> {
                val sourcecode = loadAsmIncludeFile(stmt.args[0].str!!, stmt.definingModule().source)
                val scopeprefix = stmt.args[1].str ?: ""
                if(!scopeprefix.isBlank())
                    out("$scopeprefix\t.proc")
                assemblyLines.add(sourcecode.trimEnd().trimStart('\n'))
                if(!scopeprefix.isBlank())
                    out("  .pend\n")
            }
            "%asmbinary" -> {
                val offset = if(stmt.args.size>1) ", ${stmt.args[1].int}" else ""
                val length = if(stmt.args.size>2) ", ${stmt.args[2].int}" else ""
                out("  .binary \"${stmt.args[0].str}\" $offset $length")
            }
            "%breakpoint" -> {
                val label = "_prog8_breakpoint_${breakpointLabels.size+1}"
                breakpointLabels.add(label)
                out("$label\tnop")
            }
        }
    }

    private fun translate(jmp: Jump) {
        out("  jmp  ${getJumpTarget(jmp)}")
    }

    private fun getJumpTarget(jmp: Jump): String {
        return when {
            jmp.identifier!=null -> asmIdentifierName(jmp.identifier)
            jmp.generatedLabel!=null -> jmp.generatedLabel
            jmp.address!=null -> jmp.address.toHex()
            else -> "????"
        }
    }

    private fun translate(ret: Return) {
        ret.value?.let { expressionsAsmGen.translateExpression(it) }
        out("  rts")
    }

    private fun translate(asm: InlineAssembly) {
        val assembly = asm.assembly.trimEnd().trimStart('\n')
        assemblyLines.add(assembly)
    }

    internal fun translateArrayIndexIntoA(expr: ArrayIndexedExpression) {
        when (val index = expr.arrayspec.index) {
            is NumericLiteralValue -> throw AssemblyError("this should be optimized directly")
            is RegisterExpr -> {
                when (index.register) {
                    Register.A -> {}
                    Register.X -> out("  txa")
                    Register.Y -> out("  tya")
                }
            }
            is IdentifierReference -> {
                val indexName = asmIdentifierName(index)
                out("  lda  $indexName")
            }
            else -> {
                expressionsAsmGen.translateExpression(index)
                out("  inx |  lda  $ESTACK_LO_HEX,x")
            }
        }
    }

    internal fun translateExpression(expression: Expression) =
            expressionsAsmGen.translateExpression(expression)

    internal fun translateFunctioncallExpression(functionCall: FunctionCall, signature: FunctionSignature) =
            builtinFunctionsAsmGen.translateFunctioncallExpression(functionCall, signature)

    internal fun translateFunctionCall(functionCall: FunctionCall) =
            functioncallAsmGen.translateFunctionCall(functionCall)

    internal fun assignFromEvalResult(target: AssignTarget) =
            assignmentAsmGen.assignFromEvalResult(target)

    fun assignFromByteConstant(target: AssignTarget, value: Short) =
            assignmentAsmGen.assignFromByteConstant(target, value)

    fun assignFromWordConstant(target: AssignTarget, value: Int) =
            assignmentAsmGen.assignFromWordConstant(target, value)

    fun assignFromFloatConstant(target: AssignTarget, value: Double) =
            assignmentAsmGen.assignFromFloatConstant(target, value)

    fun assignFromByteVariable(target: AssignTarget, variable: IdentifierReference) =
            assignmentAsmGen.assignFromByteVariable(target, variable)

    fun assignFromWordVariable(target: AssignTarget, variable: IdentifierReference) =
            assignmentAsmGen.assignFromWordVariable(target, variable)

    fun assignFromFloatVariable(target: AssignTarget, variable: IdentifierReference) =
            assignmentAsmGen.assignFromFloatVariable(target, variable)

    fun assignFromRegister(target: AssignTarget, register: Register) =
            assignmentAsmGen.assignFromRegister(target, register)

    fun assignFromMemoryByte(target: AssignTarget, address: Int?, identifier: IdentifierReference?) =
            assignmentAsmGen.assignFromMemoryByte(target, address, identifier)
}
