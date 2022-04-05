package org.jetbrains.research.kex.reanimator.actionsequence.generator

import org.jetbrains.research.kex.descriptor.*
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.reanimator.actionsequence.*
import org.jetbrains.research.kex.smt.Checker
import org.jetbrains.research.kex.smt.Result
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.term.FieldTerm
import org.jetbrains.research.kex.state.term.term
import org.jetbrains.research.kex.state.transformer.TypeInfoMap
import org.jetbrains.research.kex.state.transformer.generateFinalDescriptors
import org.jetbrains.research.kex.util.asArray
import org.jetbrains.research.kex.util.field
import org.jetbrains.research.kfg.type.ClassType
import org.jetbrains.research.kfg.type.SystemTypeNames
import org.jetbrains.research.kthelper.logging.log

private fun computeEnumConstants(ctx: GeneratorContext, enumType: KexType): Map<FieldTerm, Descriptor> =
    with(ctx) {
        val kfgType = enumType.getKfgType(context.types) as ClassType
        val staticInit = kfgType.klass.getMethod("<clinit>", "()V")

        val state = staticInit.methodState ?: return mapOf()
        val preparedState = state.prepare(staticInit, TypeInfoMap())
        val queryBuilder = StateBuilder()
        with(queryBuilder) {
            val enumArray = KexArray(enumType)
            val valuesField = term { staticRef(enumType as KexClass).field(enumArray, "\$VALUES") }
            val generatedTerm = term { generate(enumArray) }
            state { generatedTerm equality valuesField.load() }
            require { generatedTerm inequality null }
        }
        val preparedQuery = prepareQuery(queryBuilder.apply())

        val checker = Checker(staticInit, context, psa)
        val params = when (val result = checker.check(preparedState + preparedQuery)) {
            is Result.SatResult -> {
                log.debug("Model: ${result.model}")
                generateFinalDescriptors(staticInit, context, result.model, checker.state)
            }
            else -> null
        } ?: return mapOf()

        val staticFields = params.statics
            .mapNotNull { it as? ClassDescriptor }
            .first { it.klass == enumType }
            .fields

        return staticFields.map { (field, value) ->
            term { staticRef(enumType as KexClass).field(field.second, field.first) } as FieldTerm to value
        }.toMap()
    }

private fun Descriptor.matches(
    other: Descriptor,
    visited: MutableMap<Pair<Descriptor, Descriptor>, Boolean>
): Boolean = when {
    this.javaClass != other.javaClass -> {
        visited[this to other] = false
        false
    }
    this is ConstantDescriptor.Null -> other is ConstantDescriptor.Null
    this is ConstantDescriptor.Bool -> this.value == (other as? ConstantDescriptor.Bool)?.value
    this is ConstantDescriptor.Byte -> this.value == (other as? ConstantDescriptor.Byte)?.value
    this is ConstantDescriptor.Char -> this.value == (other as? ConstantDescriptor.Char)?.value
    this is ConstantDescriptor.Short -> this.value == (other as? ConstantDescriptor.Short)?.value
    this is ConstantDescriptor.Int -> this.value == (other as? ConstantDescriptor.Int)?.value
    this is ConstantDescriptor.Long -> this.value == (other as? ConstantDescriptor.Long)?.value
    this is ConstantDescriptor.Float -> this.value == (other as? ConstantDescriptor.Float)?.value
    this is ConstantDescriptor.Double -> this.value == (other as? ConstantDescriptor.Double)?.value
    this is ObjectDescriptor -> {
        other as ObjectDescriptor
        visited[this to other] = false
        val res = other.fields.all { (field, value) ->
            val thisField = this[field] ?: return@all false
            value.matches(thisField, visited)
        }
        visited[this to other] = res
        res
    }
    this is ArrayDescriptor -> {
        other as ArrayDescriptor
        visited[this to other] = false
        if (this.length == other.length) {
            val res = other.elements.all { (index, value) ->
                val thisField = this[index] ?: return@all false
                value.matches(thisField, visited)
            }
            visited[this to other] = res
            res
        } else {
            false
        }
    }
    else -> false
}

class EnumGenerator(private val fallback: Generator) : Generator {
    companion object {
        private val enumConstants = mutableMapOf<KexType, Map<FieldTerm, Descriptor>>()
        private var enumCounter = 0

        private fun getEnumConstants(ctx: GeneratorContext, enumType: KexType): Map<FieldTerm, Descriptor> =
            enumConstants.getOrPut(enumType) {
                computeEnumConstants(ctx, enumType)
            }
    }

    override val context: GeneratorContext
        get() = fallback.context

    override fun supports(descriptor: Descriptor): Boolean {
        val type = descriptor.type
        return when (val kfgType = type.getKfgType(context.types)) {
            is ClassType -> kfgType.klass.isEnum
            else -> false
        }
    }

    private fun getEnumName() = "enum${enumCounter++}"

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence = with(context) {
        val name = descriptor.term.toString()
        val list = ActionList(getEnumName())
        saveToCache(descriptor, list)

        val kfgType = descriptor.type.getKfgType(context.types) as ClassType
        val enumConstants = getEnumConstants(this, descriptor.type).toList()
        list += when (descriptor) {
            is ClassDescriptor -> {
                val klass = kfgType.klass
                val valuesMethod = klass.getMethod("values", klass.type.asArray(context.types))
                StaticMethodCall(valuesMethod, emptyList())
            }
            else -> {
                val result = enumConstants.firstOrNull { it.second.matches(descriptor, mutableMapOf()) }
                    ?: enumConstants.filter { it.second.type == descriptor.type }.randomOrNull()
                    ?: return UnknownSequence(name, kfgType, descriptor).also {
                        saveToCache(descriptor, it)
                    }
                EnumValueCreation(cm[result.first.klass], result.first.fieldName)
            }
        }
        return list
    }
}


class ReflectionEnumGenerator(private val fallback: Generator) : Generator {
    companion object {
        private val enumConstants = mutableMapOf<KexType, Map<FieldTerm, Descriptor>>()
        private var enumCounter = 0

        private fun getEnumConstants(ctx: GeneratorContext, enumType: KexType): Map<FieldTerm, Descriptor> =
            enumConstants.getOrPut(enumType) {
                computeEnumConstants(ctx, enumType)
            }
    }

    override val context: GeneratorContext
        get() = fallback.context

    val kfgFieldClass = context.cm[SystemTypeNames.field]

    private fun getEnumName() = "enum${enumCounter++}"

    override fun supports(descriptor: Descriptor): Boolean {
        val type = descriptor.type
        return when (val kfgType = type.getKfgType(context.types)) {
            is ClassType -> kfgType.klass.isEnum
            else -> false
        }
    }

    override fun generate(descriptor: Descriptor, generationDepth: Int): ActionSequence = with(context) {
        val name = descriptor.term.toString()
        val list = ActionList(getEnumName())
        saveToCache(descriptor, list)

        val kfgType = descriptor.type.getKfgType(context.types) as ClassType
        val enumConstants = getEnumConstants(this, descriptor.type).toList()
        val getMethod = kfgFieldClass.getMethod("get", types.objectType, types.objectType)

        val valuesFieldDescriptor = descriptor {
            val desc = `object`(kfgFieldClass.kexType)
            val clazz = `object`(KexJavaClass())
            clazz["name" to KexString()] = string(kfgType.klass.canonicalDesc)
            desc["clazz" to KexJavaClass()] = clazz
            desc
        } as ObjectDescriptor
        list += when (descriptor) {
            is ClassDescriptor -> {
                valuesFieldDescriptor["name" to KexString()] = descriptor { string("\$VALUES") }
                val fieldAS = fallback.generate(valuesFieldDescriptor, generationDepth)
                ExternalMethodCall(getMethod, fieldAS, listOf(PrimaryValue(null)))
            }
            else -> {
                val enumPair = enumConstants.firstOrNull { it.second.matches(descriptor, mutableMapOf()) }
                    ?: enumConstants.filter { it.second.type == descriptor.type }.randomOrNull()
                    ?: return UnknownSequence(name, kfgType, descriptor).also {
                        saveToCache(descriptor, it)
                    }
                valuesFieldDescriptor["name" to KexString()] = descriptor { string(enumPair.first.fieldName) }
                val fieldAS = fallback.generate(valuesFieldDescriptor, generationDepth)
                ExternalMethodCall(getMethod, fieldAS, listOf(PrimaryValue(null)))
            }
        }
        return list
    }
}