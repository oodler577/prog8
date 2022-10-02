package prog8.codegen.intermediate

import prog8.code.ast.*
import prog8.code.core.AssemblyError
import prog8.code.core.DataType
import prog8.code.core.Position
import prog8.code.core.SignedDatatypes
import prog8.intermediate.IRCodeChunk
import prog8.intermediate.IRInstruction
import prog8.intermediate.Opcode
import prog8.intermediate.IRDataType

internal class AssignmentGen(private val codeGen: IRCodeGen, private val expressionEval: ExpressionGen) {

    internal fun translate(assignment: PtAssignment): IRCodeChunk {
        if(assignment.target.children.single() is PtMachineRegister)
            throw AssemblyError("assigning to a register should be done by just evaluating the expression into resultregister")

        return if (assignment.isInplaceAssign)
            translateInplaceAssign(assignment)
        else
            translateRegularAssign(assignment)
    }

    private fun translateInplaceAssign(assignment: PtAssignment): IRCodeChunk {
        val ident = assignment.target.identifier
        val memory = assignment.target.memory
        val array = assignment.target.array

        return if(ident!=null) {
            assignSelfInMemory(ident.targetName.joinToString("."), assignment.value, assignment)
        } else if(memory != null) {
            if(memory.address is PtNumber)
                assignSelfInMemoryKnownAddress((memory.address as PtNumber).number.toInt(), assignment.value, assignment)
            else
                fallbackAssign(assignment)
        } else if(array!=null) {
            // NOTE: naive fallback assignment here will sometimes generate code that loads the index value multiple times
            // in a register. It's way too much work to optimize that here - instead, we trust that the generated IL assembly
            // will be optimized later and have the double assignments removed.
            fallbackAssign(assignment)
        } else {
            fallbackAssign(assignment)
        }
    }

    private fun assignSelfInMemoryKnownAddress(
        address: Int,
        value: PtExpression,
        origAssign: PtAssignment
    ): IRCodeChunk {
        val vmDt = codeGen.irType(value.type)
        val code = IRCodeChunk(origAssign.position)
        when(value) {
            is PtIdentifier -> return code // do nothing, x=x null assignment.
            is PtMachineRegister -> return code // do nothing, reg=reg null assignment
            is PtPrefix -> return inplacePrefix(value.operator, vmDt, address, null, value.position)
            is PtBinaryExpression -> return inplaceBinexpr(value.operator, value.right, vmDt, value.type in SignedDatatypes, address, null, origAssign)
            is PtMemoryByte -> {
                return if (!codeGen.options.compTarget.machine.isIOAddress(address.toUInt()))
                    code // do nothing, mem=mem null assignment.
                else {
                    // read and write a (i/o) memory location to itself.
                    val tempReg = codeGen.registers.nextFree()
                    code += IRInstruction(Opcode.LOADM, vmDt, reg1 = tempReg, value = address)
                    code += IRInstruction(Opcode.STOREM, vmDt, reg1 = tempReg, value = address)
                    code
                }
            }
            else -> return fallbackAssign(origAssign)
        }
    }

    private fun assignSelfInMemory(
        symbol: String,
        value: PtExpression,
        origAssign: PtAssignment
    ): IRCodeChunk {
        val vmDt = codeGen.irType(value.type)
        val code = IRCodeChunk(origAssign.position)
        when(value) {
            is PtIdentifier -> return code // do nothing, x=x null assignment.
            is PtMachineRegister -> return code // do nothing, reg=reg null assignment
            is PtPrefix -> return inplacePrefix(value.operator, vmDt, null, symbol, value.position)
            is PtBinaryExpression -> return inplaceBinexpr(value.operator, value.right, vmDt, value.type in SignedDatatypes, null, symbol, origAssign)
            is PtMemoryByte -> {
                val tempReg = codeGen.registers.nextFree()
                code += IRInstruction(Opcode.LOADM, vmDt, reg1 = tempReg, labelSymbol = symbol)
                code += IRInstruction(Opcode.STOREM, vmDt, reg1 = tempReg, labelSymbol = symbol)
                return code
            }
            else -> return fallbackAssign(origAssign)
        }
    }

    private fun fallbackAssign(origAssign: PtAssignment): IRCodeChunk {
        if (codeGen.options.slowCodegenWarnings)
            codeGen.errors.warn("indirect code for in-place assignment", origAssign.position)
        return translateRegularAssign(origAssign)
    }

    private fun inplaceBinexpr(
        operator: String,
        operand: PtExpression,
        vmDt: IRDataType,
        signed: Boolean,
        knownAddress: Int?,
        symbol: String?,
        origAssign: PtAssignment
    ): IRCodeChunk {
        if(knownAddress!=null) {
            when (operator) {
                "+" -> return expressionEval.operatorPlusInplace(knownAddress, null, vmDt, operand)
                "-" -> return expressionEval.operatorMinusInplace(knownAddress, null, vmDt, operand)
                "*" -> return expressionEval.operatorMultiplyInplace(knownAddress, null, vmDt, operand)
                "/" -> return expressionEval.operatorDivideInplace(knownAddress, null, vmDt, signed, operand)
                "|" -> return expressionEval.operatorOrInplace(knownAddress, null, vmDt, operand)
                "&" -> return expressionEval.operatorAndInplace(knownAddress, null, vmDt, operand)
                "^" -> return expressionEval.operatorXorInplace(knownAddress, null, vmDt, operand)
                "<<" -> return expressionEval.operatorShiftLeftInplace(knownAddress, null, vmDt, operand)
                ">>" -> return expressionEval.operatorShiftRightInplace(knownAddress, null, vmDt, signed, operand)
                else -> {}
            }
        } else {
            symbol!!
            when (operator) {
                "+" -> return expressionEval.operatorPlusInplace(null, symbol, vmDt, operand)
                "-" -> return expressionEval.operatorMinusInplace(null, symbol, vmDt, operand)
                "*" -> return expressionEval.operatorMultiplyInplace(null, symbol, vmDt, operand)
                "/" -> return expressionEval.operatorDivideInplace(null, symbol, vmDt, signed, operand)
                "|" -> return expressionEval.operatorOrInplace(null, symbol, vmDt, operand)
                "&" -> return expressionEval.operatorAndInplace(null, symbol, vmDt, operand)
                "^" -> return expressionEval.operatorXorInplace(null, symbol, vmDt, operand)
                "<<" -> return expressionEval.operatorShiftLeftInplace(null, symbol, vmDt, operand)
                ">>" -> return expressionEval.operatorShiftRightInplace(null, symbol, vmDt, signed, operand)
                else -> {}
            }
        }
        return fallbackAssign(origAssign)
    }

    private fun inplacePrefix(operator: String, vmDt: IRDataType, knownAddress: Int?, addressSymbol: String?, position: Position): IRCodeChunk {
        val code= IRCodeChunk(position)
        when(operator) {
            "+" -> { }
            "-" -> {
                code += if(knownAddress!=null)
                    IRInstruction(Opcode.NEGM, vmDt, value = knownAddress)
                else
                    IRInstruction(Opcode.NEGM, vmDt, labelSymbol = addressSymbol)
            }
            "~" -> {
                val regMask = codeGen.registers.nextFree()
                val mask = if(vmDt==IRDataType.BYTE) 0x00ff else 0xffff
                code += IRInstruction(Opcode.LOAD, vmDt, reg1=regMask, value = mask)
                code += if(knownAddress!=null)
                    IRInstruction(Opcode.XORM, vmDt, reg1=regMask, value = knownAddress)
                else
                    IRInstruction(Opcode.XORM, vmDt, reg1=regMask, labelSymbol = addressSymbol)
            }
            else -> throw AssemblyError("weird prefix operator")
        }
        return code
    }

    private fun translateRegularAssign(assignment: PtAssignment): IRCodeChunk {
        // note: assigning array and string values is done via an explicit memcopy/stringcopy function call.
        val ident = assignment.target.identifier
        val memory = assignment.target.memory
        val array = assignment.target.array
        val vmDt = codeGen.irType(assignment.value.type)

        val code = IRCodeChunk(assignment.position)
        var resultRegister = -1
        var resultFpRegister = -1
        val zero = codeGen.isZero(assignment.value)
        if(!zero) {
            // calculate the assignment value
            if (vmDt == IRDataType.FLOAT) {
                resultFpRegister = codeGen.registers.nextFreeFloat()
                code += expressionEval.translateExpression(assignment.value, -1, resultFpRegister)
            } else {
                resultRegister = if (assignment.value is PtMachineRegister) {
                    (assignment.value as PtMachineRegister).register
                } else {
                    val reg = codeGen.registers.nextFree()
                    code += expressionEval.translateExpression(assignment.value, reg, -1)
                    reg
                }
            }
        }
        if(ident!=null) {
            val symbol = ident.targetName.joinToString(".")
            code += if(zero) {
                IRInstruction(Opcode.STOREZM, vmDt, labelSymbol = symbol)
            } else {
                if (vmDt == IRDataType.FLOAT)
                    IRInstruction(Opcode.STOREM, vmDt, fpReg1 = resultFpRegister, labelSymbol = symbol)
                else
                    IRInstruction(Opcode.STOREM, vmDt, reg1 = resultRegister, labelSymbol = symbol)
            }
        }
        else if(array!=null) {
            val variable = array.variable.targetName.joinToString(".")
            val itemsize = codeGen.program.memsizer.memorySize(array.type)

            if(array.variable.type==DataType.UWORD) {
                // indexing a pointer var instead of a real array or string
                if(itemsize!=1)
                    throw AssemblyError("non-array var indexing requires bytes dt")
                if(array.index.type!=DataType.UBYTE)
                    throw AssemblyError("non-array var indexing requires bytes index")
                val idxReg = codeGen.registers.nextFree()
                code += expressionEval.translateExpression(array.index, idxReg, -1)
                if(zero) {
                    // there's no STOREZIX instruction
                    resultRegister = codeGen.registers.nextFree()
                    code += IRInstruction(Opcode.LOAD, vmDt, reg1=resultRegister, value=0)
                }
                code += IRInstruction(Opcode.STOREIX, vmDt, reg1=resultRegister, reg2=idxReg, labelSymbol = variable)
                return code
            }

            val fixedIndex = constIntValue(array.index)
            if(zero) {
                if(fixedIndex!=null) {
                    val offset = fixedIndex*itemsize
                    code += IRInstruction(Opcode.STOREZM, vmDt, labelSymbol = "$variable+$offset")
                } else {
                    val indexReg = codeGen.registers.nextFree()
                    code += loadIndexReg(array, itemsize, indexReg, array.position)
                    code += IRInstruction(Opcode.STOREZX, vmDt, reg1=indexReg, labelSymbol = variable)
                }
            } else {
                if(vmDt== IRDataType.FLOAT) {
                    if(fixedIndex!=null) {
                        val offset = fixedIndex*itemsize
                        code += IRInstruction(Opcode.STOREM, vmDt, fpReg1 = resultFpRegister, labelSymbol = "$variable+$offset")
                    } else {
                        val indexReg = codeGen.registers.nextFree()
                        code += loadIndexReg(array, itemsize, indexReg, array.position)
                        code += IRInstruction(Opcode.STOREX, vmDt, reg1 = resultRegister, reg2=indexReg, labelSymbol = variable)
                    }
                } else {
                    if(fixedIndex!=null) {
                        val offset = fixedIndex*itemsize
                        code += IRInstruction(Opcode.STOREM, vmDt, reg1 = resultRegister, labelSymbol = "$variable+$offset")
                    } else {
                        val indexReg = codeGen.registers.nextFree()
                        code += loadIndexReg(array, itemsize, indexReg, array.position)
                        code += IRInstruction(Opcode.STOREX, vmDt, reg1 = resultRegister, reg2=indexReg, labelSymbol = variable)
                    }
                }
            }
        }
        else if(memory!=null) {
            require(vmDt== IRDataType.BYTE)
            if(zero) {
                if(memory.address is PtNumber) {
                    code += IRInstruction(Opcode.STOREZM, vmDt, value=(memory.address as PtNumber).number.toInt())
                } else {
                    val addressReg = codeGen.registers.nextFree()
                    code += expressionEval.translateExpression(memory.address, addressReg, -1)
                    code += IRInstruction(Opcode.STOREZI, vmDt, reg1=addressReg)
                }
            } else {
                if(memory.address is PtNumber) {
                    code += IRInstruction(Opcode.STOREM, vmDt, reg1=resultRegister, value=(memory.address as PtNumber).number.toInt())
                } else {
                    val addressReg = codeGen.registers.nextFree()
                    code += expressionEval.translateExpression(memory.address, addressReg, -1)
                    code += IRInstruction(Opcode.STOREI, vmDt, reg1=resultRegister, reg2=addressReg)
                }
            }
        }
        else
            throw AssemblyError("weird assigntarget")
        return code
    }

    private fun loadIndexReg(array: PtArrayIndexer, itemsize: Int, indexReg: Int, position: Position): IRCodeChunk {
        val code = IRCodeChunk(position)
        if(itemsize==1) {
            code += expressionEval.translateExpression(array.index, indexReg, -1)
        }
        else {
            val mult = PtBinaryExpression("*", DataType.UBYTE, array.position)
            mult.children += array.index
            mult.children += PtNumber(DataType.UBYTE, itemsize.toDouble(), array.position)
            code += expressionEval.translateExpression(mult, indexReg, -1)
        }
        return code
    }
}