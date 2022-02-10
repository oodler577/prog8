package prog8.ast

import prog8.ast.base.Position
import prog8.ast.expressions.Expression
import prog8.ast.expressions.InferredTypes
import prog8.ast.expressions.NumericLiteral

interface IBuiltinFunctions {
    val names: Set<String>
    val purefunctionNames: Set<String>
    fun constValue(name: String, args: List<Expression>, position: Position): NumericLiteral?
    fun returnType(name: String, args: MutableList<Expression>): InferredTypes.InferredType
}
