/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.intrinsic.operation

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.backend.ast.JsBinaryOperation
import org.jetbrains.kotlin.js.backend.ast.JsBinaryOperator
import org.jetbrains.kotlin.js.backend.ast.JsExpression
import org.jetbrains.kotlin.js.backend.ast.JsNullLiteral
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.intrinsic.functions.factories.TopLevelFIF
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.PsiUtils.getOperationToken
import org.jetbrains.kotlin.js.translate.utils.TranslationUtils
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.types.isDynamic
import org.jetbrains.kotlin.types.typeUtil.makeNullable

object EqualsBOIF : BinaryOperationIntrinsicFactory {
    private object EqualsIntrinsic : AbstractBinaryOperationIntrinsic() {

        override fun apply(expression: KtBinaryExpression, left: JsExpression, right: JsExpression, context: TranslationContext): JsExpression {
            val isNegated = expression.isNegated()
            val anyType = context.currentModule.builtIns.anyType
            if (right is JsNullLiteral || left is JsNullLiteral) {
                val (subject, ktSubject) = if (right is JsNullLiteral) Pair(left, expression.left!!) else Pair(right, expression.right!!)
                val type = context.bindingContext().getType(ktSubject) ?: anyType
                val coercedSubject = TranslationUtils.coerce(context, subject, type.makeNullable())
                return TranslationUtils.nullCheck(coercedSubject, isNegated)
            }

            val (leftKotlinType, rightKotlinType) = binaryOperationTypes(expression, context)

            if (KotlinBuiltIns.isPrimitiveTypeOrNullablePrimitiveType(leftKotlinType!!) && KotlinBuiltIns.isPrimitiveTypeOrNullablePrimitiveType(rightKotlinType!!) &&
                (!KotlinBuiltIns.isLongOrNullableLong(leftKotlinType) || !KotlinBuiltIns.isLongOrNullableLong(rightKotlinType))
            ) {
                val coercedLeft = context.coerceAndConvertLong(left, leftKotlinType)
                val coercedRight = context.coerceAndConvertLong(right, rightKotlinType)
                return JsBinaryOperation(if (isNegated) JsBinaryOperator.REF_NEQ else JsBinaryOperator.REF_EQ, coercedLeft, coercedRight)
            }
            val resolvedCall = expression.getResolvedCall(context.bindingContext())
            val appliedToDynamic =
                resolvedCall != null &&
                        with(resolvedCall.dispatchReceiver) {
                            if (this != null) type.isDynamic() else false
                        }

            if (appliedToDynamic) {
                return JsBinaryOperation(if (isNegated) JsBinaryOperator.NEQ else JsBinaryOperator.EQ, left, right)
            }

            val coercedLeft = TranslationUtils.coerce(context, left, anyType)
            val coercedRight = TranslationUtils.coerce(context, right, anyType)
            val result = TopLevelFIF.KOTLIN_EQUALS.apply(coercedLeft, listOf(coercedRight), context)
            return if (isNegated) JsAstUtils.not(result) else result
        }

        private fun TranslationContext.coerceAndConvertLong(expression: JsExpression, type: KotlinType): JsExpression {
            val number = if (KotlinBuiltIns.isLongOrNullableLong(type)) {
                JsAstUtils.longToNumberOrNull(expression)
            } else {
                expression
            }
            return TranslationUtils.coerce(this, number, type)
        }
    }

    object EnumEqualsIntrinsic : AbstractBinaryOperationIntrinsic() {
        override fun apply(expression: KtBinaryExpression, left: JsExpression, right: JsExpression, context: TranslationContext): JsBinaryOperation {
            val operator = if (expression.isNegated()) JsBinaryOperator.REF_NEQ else JsBinaryOperator.REF_EQ
            return JsBinaryOperation(operator, left, right)
        }
    }

    override fun getSupportTokens() = OperatorConventions.EQUALS_OPERATIONS!!

    override fun getIntrinsic(descriptor: FunctionDescriptor, leftType: KotlinType?, rightType: KotlinType?): BinaryOperationIntrinsic? =
            when {
                isEnumIntrinsicApplicable(descriptor, leftType, rightType) -> EnumEqualsIntrinsic

                KotlinBuiltIns.isBuiltIn(descriptor) ||
                TopLevelFIF.EQUALS_IN_ANY.test(descriptor) -> EqualsIntrinsic

                else -> null
            }

    private fun isEnumIntrinsicApplicable(descriptor: FunctionDescriptor, leftType: KotlinType?, rightType: KotlinType?): Boolean {
        return DescriptorUtils.isEnumClass(descriptor.containingDeclaration) && leftType != null && rightType != null &&
               !TypeUtils.isNullableType(leftType) && !TypeUtils.isNullableType(rightType)
    }

    private fun KtBinaryExpression.isNegated() = getOperationToken(this) == KtTokens.EXCLEQ
}
