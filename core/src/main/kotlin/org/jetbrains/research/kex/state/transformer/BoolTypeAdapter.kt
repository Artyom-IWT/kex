package org.jetbrains.research.kex.state.transformer

import org.jetbrains.research.kex.ktype.KexBool
import org.jetbrains.research.kex.ktype.KexInt
import org.jetbrains.research.kex.state.predicate.EqualityPredicate
import org.jetbrains.research.kex.state.predicate.Predicate
import org.jetbrains.research.kex.state.term.BinaryTerm
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.util.log
import org.jetbrains.research.kex.util.unreachable
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode

object BoolTypeAdapter : Transformer<BoolTypeAdapter> {
    override fun transformEqualityPredicate(predicate: EqualityPredicate): Predicate {
        val lhv = predicate.lhv
        val rhv = predicate.rhv
        val type = predicate.type
        val loc = predicate.location
        val res = when {
            lhv.type === KexBool && rhv.type === KexInt -> pf.getEquality(lhv, tf.getCast(KexBool, rhv), type, loc)
            lhv.type === KexInt && rhv.type === KexBool -> pf.getEquality(lhv, tf.getCast(KexInt, rhv), type, loc)
            else -> predicate
        }
        return res
    }

    override fun transformBinaryTerm(term: BinaryTerm): Term {
        val isBooleanOpcode = when (term.opcode) {
            BinaryOpcode.And() -> true
            BinaryOpcode.Or() -> true
            BinaryOpcode.Xor() -> true
            else -> false
        }
        return when {
            term.lhv.type === term.rhv.type -> term
            isBooleanOpcode -> {
                val lhv = when {
                    term.lhv.type === KexBool -> tf.getCast(KexInt, term.lhv)
                    term.lhv.type === KexInt -> term.lhv
                    else -> unreachable { log.error("Non-boolean term in boolean binary: ${term.print()}") }
                }
                val rhv = when {
                    term.rhv.type === KexBool -> tf.getCast(KexInt, term.rhv)
                    term.rhv.type === KexInt -> term.rhv
                    else -> unreachable { log.error("Non-boolean term in boolean binary: ${term.print()}") }
                }
                tf.getBinary(term.opcode, lhv, rhv) as BinaryTerm
            }
            else -> term
        }
    }
}