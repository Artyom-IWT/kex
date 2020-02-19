package org.jetbrains.research.kex.generator

import com.abdullin.kthelper.collection.queueOf
import com.abdullin.kthelper.logging.log
import com.abdullin.kthelper.tryOrNull
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.annotations.AnnotationManager
import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.ktype.KexType
import org.jetbrains.research.kex.ktype.kexType
import org.jetbrains.research.kex.smt.Checker
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.term
import org.jetbrains.research.kex.state.transformer.*
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.Node
import org.jetbrains.research.kfg.type.ArrayType
import org.jetbrains.research.kfg.type.Type

enum class Visibility {
    PRIVATE,
    PROTECTED,
    PACKAGE,
    PUBLIC;
}

val Node.visibility: Visibility
    get() = when {
        this.isPrivate -> Visibility.PRIVATE
        this.isProtected -> Visibility.PROTECTED
        this.isPublic -> Visibility.PUBLIC
        else -> Visibility.PACKAGE
    }

private val visibilityLevel by lazy { kexConfig.getEnumValue("apiGeneration", "visibility", true, Visibility.PUBLIC) }
private val maxStackSize by lazy { kexConfig.getIntValue("apiGeneration", "maxStackSize", 5) }
private val isInliningEnabled by lazy { kexConfig.getBooleanValue("smt", "ps-inlining", true) }
private val annotationsEnabled by lazy { kexConfig.getBooleanValue("annotations", "enabled", false) }

// todo: generation of abstract classes and interfaces
// todo: think about generating list of calls instead of call stack tree
// todo: complex relations between descriptors (not just equals to constant, but also equals to each other)
class CallStackGenerator(val context: ExecutionContext, val psa: PredicateStateAnalysis) {
    private val descriptorMap = mutableMapOf<Descriptor, Node>()

    private fun prepareState(method: Method, ps: PredicateState, ignores: Set<Term> = setOf()) = transform(ps) {
        if (annotationsEnabled) +AnnotationIncluder(AnnotationManager.defaultLoader)
        if (isInliningEnabled) +MethodInliner(method, psa)
        +IntrinsicAdapter
        +ReflectionInfoAdapter(method, context.loader, ignores)
        +Optimizer()
        +ConstantPropagator
        +BoolTypeAdapter(method.cm.type)
        +ArrayBoundsAdapter()
        +NullityInfoAdapter()
        +CastInfoAdapter(method.cm.type)
    }

    private fun prepareQuery(ps: PredicateState) = transform(ps) {
        +NullityInfoAdapter()
    }

    private class Node(var stack: CallStack) {
        constructor() : this(CallStack())

        operator fun plusAssign(apiCall: ApiCall) {
            this.stack += apiCall
        }

        operator fun plusAssign(callStack: CallStack) {
            this.stack += callStack
        }
    }

    fun generate(descriptor: Descriptor): CallStack {
        if (descriptorMap.containsKey(descriptor)) return descriptorMap.getValue(descriptor).stack

        when (descriptor) {
            is ConstantDescriptor -> return when (descriptor) {
                is ConstantDescriptor.Null -> PrimaryValue(null)
                is ConstantDescriptor.Bool -> PrimaryValue(descriptor.value)
                is ConstantDescriptor.Int -> PrimaryValue(descriptor.value)
                is ConstantDescriptor.Long -> PrimaryValue(descriptor.value)
                is ConstantDescriptor.Float -> PrimaryValue(descriptor.value)
                is ConstantDescriptor.Double -> PrimaryValue(descriptor.value)
                is ConstantDescriptor.Class -> PrimaryValue(descriptor.value)
            }.wrap()
            is ObjectDescriptor -> {
                descriptorMap[descriptor] = Node(generateObject(descriptor)
                        ?: UnknownCall(descriptor.klass, descriptor).wrap())
            }
            is ArrayDescriptor -> {
                val callStack = Node()
                descriptorMap[descriptor] = callStack

                val elementType = (descriptor.type as ArrayType).component
                val array = NewArray(elementType, PrimaryValue(descriptor.length).wrap()).wrap()
                callStack += array

                descriptor.elements.forEach { (index, value) ->
                    val arrayWrite = ArrayWrite(array, PrimaryValue(index).wrap(), generate(value))
                    callStack += arrayWrite
                }
            }
            is FieldDescriptor -> {
                val callStack = Node()
                val klass = descriptor.klass
                val field = klass.getField(descriptor.name, descriptor.type)
                descriptorMap[descriptor] = callStack

                callStack += when {
                    field.isStatic -> StaticFieldSetter(klass, field, generate(descriptor.value))
                    else -> FieldSetter(klass, generate(descriptor.owner), field, generate(descriptor.value))
                }
            }
        }
        return descriptorMap.getValue(descriptor).stack
    }

    private fun generateObject(descriptor: ObjectDescriptor): CallStack? {
        val instantiableDescriptor = tryOrNull { descriptor.instantiableDescriptor } ?: return null
        val klass = instantiableDescriptor.klass

        val queue = queueOf(generateSetters(instantiableDescriptor.reduced))
        while (queue.isNotEmpty()) {
            val (desc, stack) = queue.poll()
            if (stack.stack.size > maxStackSize) continue

            // try to generate constructor call
            for (method in klass.accessibleConstructors) {
                val (thisDesc, args) = method.executeAsConstructor(descriptor) ?: continue

                if (thisDesc.isFinal(descriptor)) {
                    val constructorCall = when {
                        method.argTypes.isEmpty() -> DefaultConstructorCall(klass)
                        else -> ConstructorCall(klass, method, args.map { generate(it) })
                    }
                    return stack + constructorCall
                }
            }

            // execute available methods
            for (method in klass.accessibleMethods) {
                val (result, args) = method.executeAsMethod(desc) ?: continue
                if (result != null && result != desc) {
                    val newStack = stack + MethodCall(stack, method, args.map { generate(it) })
                    val newDesc = result.merge(desc)
                    queue += newDesc to newStack
                }
            }
        }
        return null
    }

    private fun generateSetters(descriptor: ObjectDescriptor): Pair<ObjectDescriptor, CallStack> {
        log.info("Generating setters for $descriptor")
        var desc = descriptor
        val targetFields = desc.fields.toList()
        var callStack = CallStack()
        for ((name, fd) in targetFields) {
            val field = desc.klass.getField(name, fd.type)
            if (field.hasSetter) {
                log.info("Using setter for $field")
                val newDesc = ObjectDescriptor(desc.klass)
                newDesc[name] = fd.copy(owner = newDesc)

                val (result, args) = field.setter.executeAsMethod(newDesc) ?: continue
                if (result != null && result != desc) {
                    callStack += MethodCall(callStack, field.setter, args.map { generate(it) })
                    val newFields = desc.fields.filter { it.key != name }.toMutableMap()
                    desc = desc.copy(fieldsInner = newFields)
                    log.info("Used setter for field $field, new desc: $desc")
                }
            }
        }
        return desc to callStack
    }

    private val Class.accessibleConstructors get() = constructors.filter { visibilityLevel <= it.visibility }
    private val Class.accessibleMethods get() = methods.filter { visibilityLevel <= it.visibility }

    // todo: check more options of instantiable classes
    private val Class.isInstantiable: Boolean
        get() = when {
            this.isAbstract -> false
            this.isInterface -> false
            else -> true
        }

    private fun Method.executeAsConstructor(descriptor: ObjectDescriptor): Pair<ObjectDescriptor?, List<Descriptor>>? {
        if (isEmpty()) return null
        log.debug("Executing constructor $this for $descriptor")

        val mapper = descriptor.mapper
        val preState = mapper.apply(descriptor.preState)
        val state = preState + mapper.apply(methodState ?: return null)

        val preStateFieldTerms = collectFieldTerms(context, preState)
        val preparedState = prepareState(this, state, preStateFieldTerms)
        val preparedQuery = prepareQuery(mapper.apply(descriptor.toState()))
        return execute(preparedState, preparedQuery)
    }

    private fun Method.executeAsMethod(descriptor: ObjectDescriptor): Pair<ObjectDescriptor?, List<Descriptor>>? {
        if (isEmpty()) return null
        log.debug("Executing method $this for $descriptor")

        val mapper = descriptor.mapper
        val preState = getPreState(descriptor) ?: return null
        val preStateFieldTerms = collectFieldTerms(context, preState)
        val state = mapper.apply(preState + (methodState ?: return null))
        val preparedState = prepareState(this, state, preStateFieldTerms)
        val preparedQuery = prepareQuery(mapper.apply(descriptor.toState()))
        return execute(preparedState, preparedQuery)
    }

    private fun Method.execute(state: PredicateState, query: PredicateState): Pair<ObjectDescriptor?, List<Descriptor>>? {
        val checker = Checker(this, context.loader, psa)
        return when (val result = checker.check(state + query)) {
            is Result.SatResult -> {
                log.debug("Model: ${result.model}")
                val (thisDescriptor, argumentDescriptors) =
                        generateInitialDescriptors(this, context, result.model, checker.state)
                (thisDescriptor as? ObjectDescriptor)?.reduced to argumentDescriptors
            }
            else -> null
        }
    }

    private val Method.methodState get() = psa.builder(this).methodState

    private fun Method.getPreState(descriptor: ObjectDescriptor): PredicateState? {
        val mapper = descriptor.mapper
        val fieldAccessList = this.fieldAccesses
        val intersection = descriptor.fields.values.filter {
            fieldAccessList.find { field -> it.name == field.name && it.klass == field.`class` } != null
        }
        if (intersection.isEmpty()) return null

        val preStateBuilder = StateBuilder()
        for (field in intersection) {
            preStateBuilder.run {
                val tempTerm = term { generate(field.type.kexType) }
                state { tempTerm equality field.term.load() }
                assume { tempTerm equality field.type.defaultDescriptor.term }
            }
        }
        return mapper.apply(preStateBuilder.apply())
    }

    private val ObjectDescriptor.preState: PredicateState
        get() {
            val preState = StateBuilder()
            for (field in fields.values) {
                preState.run {
                    val tempTerm = term { generate(field.type.kexType) }
                    state { tempTerm equality field.term.load() }
                    assume { tempTerm equality field.type.kexType.defaultDescriptor.term }
                }
            }

            return preState.apply()
        }

    private val ObjectDescriptor.mapper get() = TermRemapper(mapOf(term to term { `this`(term.type) }))

    private val ObjectDescriptor.instantiableDescriptor
        get() = when {
            this.klass.isInstantiable -> this
            else -> copy(klass = context.cm.concreteClasses.filter {
                klass.isAncestorOf(it) && it.isInstantiable && visibilityLevel <= it.visibility
            }.random())
        }

    private fun ObjectDescriptor?.isFinal(original: ObjectDescriptor) = when {
        this == null -> true
        original.fields.all { this[it.key]?.isDefault ?: return@all true } -> true
        else -> false
    }

    private val ObjectDescriptor.reduced: ObjectDescriptor
        get() {
            val filteredFields = fields.filterNot { (_, field) -> field.isDefault }
            val newObject = ObjectDescriptor(klass)
            for ((name, field) in filteredFields) {
                newObject[name] = field.copy(owner = newObject)
            }
            return newObject
        }

    private val FieldDescriptor.isDefault get() = value == type.defaultDescriptor

    private val Type.defaultDescriptor get() = kexType.defaultDescriptor

    private val KexType.defaultDescriptor: Descriptor
        get() = descriptor(context) {
            default(this@defaultDescriptor)
        }
}