package org.vorpal.research.kex.state.term

import kotlinx.serialization.Serializable
import org.vorpal.research.kex.InheritorOf
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.state.transformer.Transformer

@InheritorOf("Term")
@Serializable
class CastTerm(override val type: KexType, val operand: Term) : Term() {
    override val name = "($operand as $type)"
    override val subTerms by lazy { listOf(operand) }


    override fun <T : Transformer<T>> accept(t: Transformer<T>): Term =
            when (val tOperand = t.transform(operand)) {
                operand -> this
                else -> term { termFactory.getCast(type, tOperand) }
             }
}
