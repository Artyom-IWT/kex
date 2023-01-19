package org.vorpal.research.kex.asm.analysis.symbolic

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.manager.instantiationManager
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.ktype.KexRtManager.isKexRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.OuterClass
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.instruction.CallInst
import org.vorpal.research.kfg.ir.value.instruction.CallOpcode
import org.vorpal.research.kfg.ir.value.instruction.InvokeDynamicInst
import org.vorpal.research.kfg.stringClass
import org.vorpal.research.kfg.type.ClassType


interface SymbolicCallResolver {
    fun resolve(state: TraverserState, inst: CallInst): List<Method>
}

interface SymbolicInvokeDynamicResolver {
    data class ResolvedDynamicCall(
        val instance: Value?,
        val arguments: List<Value>,
        val method: Method
    )

    fun resolve(state: TraverserState, inst: InvokeDynamicInst): ResolvedDynamicCall?
}

class DefaultCallResolver(
    val ctx: ExecutionContext,
    val converter: TraverserState.(Value) -> Term,
) : SymbolicCallResolver, SymbolicInvokeDynamicResolver {
    private val maximumNumberOfConcreteMethods = kexConfig.getIntValue(
        "symbolic", "numberOfConcreteMethods", 20
    )

    private fun shouldResolve(inst: CallInst): Boolean = when (inst.klass) {
        ctx.cm.stringClass -> false
        else -> true
    }

    override fun resolve(state: TraverserState, inst: CallInst): List<Method> {
        val method = inst.method
        if (!shouldResolve(inst)) return emptyList()
        if (method.klass is OuterClass) return emptyList()
        if (method.isNative) return emptyList()
        if (method.isStatic) return listOf(method)
        if (method.isConstructor) return listOf(method)
        if (inst.opcode == CallOpcode.SPECIAL) return listOf(inst.method)
        if (inst.opcode == CallOpcode.STATIC) return listOf(inst.method)

        val callee = state.converter(inst.callee)
        val baseType = method.klass.rtMapped
        val calleeType = (callee.type.getKfgType(ctx.types) as? ClassType)?.klass ?: return emptyList()
        return when {
            callee in state.typeInfo -> {
                val concreteType = state.typeInfo.getValue(callee) as ClassType
                listOf(concreteType.klass.getMethod(method.name, method.desc))
            }

            calleeType.isKexRt -> listOf(
                calleeType.getMethod(
                    method.name,
                    method.returnType.rtMapped,
                    *method.argTypes.map { it.rtMapped }.toTypedArray()
                )
            )

            else -> instantiationManager
                .getAllConcreteSubtypes(baseType, ctx.accessLevel)
                .filter { it.isInheritorOf(calleeType) }
                .mapTo(mutableSetOf()) {
                    when {
                        it != it.rtMapped -> it.rtMapped.getMethod(
                            method.name,
                            method.returnType.rtMapped,
                            *method.argTypes.map { arg -> arg.rtMapped }.toTypedArray()
                        )

                        it.isKexRt -> it.getMethod(
                            method.name,
                            method.returnType.rtMapped,
                            *method.argTypes.map { arg -> arg.rtMapped }.toTypedArray()
                        )

                        else -> it.getMethod(method.name, method.desc)
                    }
                }
                .filter { it.body.isNotEmpty() }
                .shuffled(ctx.random)
                .take(maximumNumberOfConcreteMethods)
        }
    }

    override fun resolve(
        state: TraverserState,
        inst: InvokeDynamicInst
    ): SymbolicInvokeDynamicResolver.ResolvedDynamicCall? = null
}
