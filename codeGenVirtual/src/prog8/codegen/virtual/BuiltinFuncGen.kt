package prog8.codegen.virtual

import prog8.code.ast.PtBuiltinFunctionCall
import prog8.code.ast.PtNumber
import prog8.code.ast.PtString
import prog8.vm.Opcode
import prog8.vm.Syscall
import prog8.vm.VmDataType

internal class BuiltinFuncGen(private val codeGen: CodeGen, private val exprGen: ExpressionGen) {

    fun translate(call: PtBuiltinFunctionCall, resultRegister: Int): VmCodeChunk {
        val code = VmCodeChunk()
        when(call.name) {
            "syscall" -> {
                val vExpr = call.args.single() as PtNumber
                code += VmCodeInstruction(Opcode.SYSCALL, value=vExpr.number.toInt())
            }
            "syscall1" -> {
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 0)
                val callNr = (call.args[0] as PtNumber).number.toInt()
                code += exprGen.translateExpression(call.args[1], 0)
                code += VmCodeInstruction(Opcode.SYSCALL, value=callNr)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 0)
            }
            "syscall2" -> {
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 0)
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 1)
                while(codeGen.vmRegisters.peekNext()<2) {
                    codeGen.vmRegisters.nextFree()
                }
                val callNr = (call.args[0] as PtNumber).number.toInt()
                code += exprGen.translateExpression(call.args[1], 0)
                code += exprGen.translateExpression(call.args[2], 1)
                code += VmCodeInstruction(Opcode.SYSCALL, value=callNr)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 1)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 0)
            }
            "syscall3" -> {
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 0)
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 1)
                code += VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1 = 2)
                while(codeGen.vmRegisters.peekNext()<3) {
                    codeGen.vmRegisters.nextFree()
                }
                val callNr = (call.args[0] as PtNumber).number.toInt()
                code += exprGen.translateExpression(call.args[1], 0)
                code += exprGen.translateExpression(call.args[2], 1)
                code += exprGen.translateExpression(call.args[3], 2)
                code += VmCodeInstruction(Opcode.SYSCALL, value=callNr)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 2)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 1)
                code += VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1 = 0)
            }
            "msb" -> {
                code += exprGen.translateExpression(call.args.single(), resultRegister)
                code += VmCodeInstruction(Opcode.SWAP, VmDataType.BYTE, reg1 = resultRegister)
                // note: if a word result is needed, the upper byte is cleared by the typecast that follows. No need to do it here.
            }
            "lsb" -> {
                code += exprGen.translateExpression(call.args.single(), resultRegister)
                // note: if a word result is needed, the upper byte is cleared by the typecast that follows. No need to do it here.
            }
            "memory" -> {
                val name = (call.args[0] as PtString).value
                val size = (call.args[1] as PtNumber).number.toUInt()
                val align = (call.args[2] as PtNumber).number.toUInt()
                val existing = codeGen.allocations.getMemorySlab(name)
                val address = if(existing==null)
                    codeGen.allocations.allocateMemorySlab(name, size, align)
                else if(existing.second!=size || existing.third!=align) {
                    codeGen.errors.err("memory slab '$name' already exists with a different size or alignment", call.position)
                    return VmCodeChunk()
                }
                else
                    existing.first
                code += VmCodeInstruction(Opcode.LOAD, VmDataType.WORD, reg1=resultRegister, value=address.toInt())
            }
            "rnd" -> {
                code += VmCodeInstruction(Opcode.SYSCALL, value= Syscall.RND.ordinal)
                if(resultRegister!=0)
                    code += VmCodeInstruction(Opcode.LOADR, VmDataType.BYTE, reg1=resultRegister, reg2=0)
            }
            "peek" -> {
                val addressReg = codeGen.vmRegisters.nextFree()
                code += exprGen.translateExpression(call.args.single(), addressReg)
                code += VmCodeInstruction(Opcode.LOADI, VmDataType.BYTE, reg1 = resultRegister, reg2=addressReg)
            }
            "peekw" -> {
                val addressReg = codeGen.vmRegisters.nextFree()
                code += exprGen.translateExpression(call.args.single(), addressReg)
                code += VmCodeInstruction(Opcode.LOADI, VmDataType.WORD, reg1 = resultRegister, reg2=addressReg)
            }
            "mkword" -> {
                val msbReg = codeGen.vmRegisters.nextFree()
                val lsbReg = codeGen.vmRegisters.nextFree()
                code += exprGen.translateExpression(call.args[0], msbReg)
                code += exprGen.translateExpression(call.args[1], lsbReg)
                code += VmCodeInstruction(Opcode.CONCAT, VmDataType.BYTE, reg1=resultRegister, reg2=msbReg, reg3=lsbReg)
            }
            "sin8u" -> {
                code += exprGen.translateExpression(call.args[0], 0)
                code += VmCodeInstruction(Opcode.SYSCALL, value=Syscall.SIN8U.ordinal)
                if(resultRegister!=0)
                    code += VmCodeInstruction(Opcode.LOADR, VmDataType.BYTE, reg1=resultRegister, reg2=0)
            }
            "cos8u" -> {
                code += exprGen.translateExpression(call.args[0], 0)
                code += VmCodeInstruction(Opcode.SYSCALL, value=Syscall.COS8U.ordinal)
                if(resultRegister!=0)
                    code += VmCodeInstruction(Opcode.LOADR, VmDataType.BYTE, reg1=resultRegister, reg2=0)
            }
            else -> {
                TODO("builtinfunc ${call.name}")
//                code += VmCodeInstruction(Opcode.NOP))
//                for (arg in call.args) {
//                    code += translateExpression(arg, resultRegister)
//                    code += when(arg.type) {
//                        in ByteDatatypes -> VmCodeInstruction(Opcode.PUSH, VmDataType.BYTE, reg1=resultRegister))
//                        in WordDatatypes -> VmCodeInstruction(Opcode.PUSH, VmDataType.WORD, reg1=resultRegister))
//                        else -> throw AssemblyError("weird arg dt")
//                    }
//                }
//                code += VmCodeInstruction(Opcode.CALL), labelArg = listOf("_prog8_builtin", call.name))
//                for (arg in call.args) {
//                    code += when(arg.type) {
//                        in ByteDatatypes -> VmCodeInstruction(Opcode.POP, VmDataType.BYTE, reg1=resultRegister))
//                        in WordDatatypes -> VmCodeInstruction(Opcode.POP, VmDataType.WORD, reg1=resultRegister))
//                        else -> throw AssemblyError("weird arg dt")
//                    }
//                }
//                code += VmCodeInstruction(Opcode.NOP))
            }
        }
        return code
    }

}
