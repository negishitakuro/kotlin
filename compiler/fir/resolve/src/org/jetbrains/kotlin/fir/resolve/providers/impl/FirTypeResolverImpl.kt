/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.providers.impl

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.ThreadSafeMutableState
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.diagnostics.ConeUnexpectedTypeArgumentsError
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeOuterClassArgumentsRequired
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeUnresolvedQualifierError
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeUnsupportedDynamicType
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeWrongNumberOfTypeArgumentsError
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.transformers.ScopeClassDeclarations
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirImplicitBuiltinTypeRef
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

@ThreadSafeMutableState
class FirTypeResolverImpl(private val session: FirSession) : FirTypeResolver() {

    private val symbolProvider by lazy {
        session.symbolProvider
    }

    private data class ClassIdInSession(val session: FirSession, val id: ClassId)

    private val implicitBuiltinTypeSymbols = mutableMapOf<ClassIdInSession, FirClassLikeSymbol<*>>()

    // TODO: get rid of session used here, and may be also of the cache above (see KT-30275)
    private fun resolveBuiltInQualified(id: ClassId, session: FirSession): FirClassLikeSymbol<*> {
        val nameInSession = ClassIdInSession(session, id)
        return implicitBuiltinTypeSymbols.getOrPut(nameInSession) {
            symbolProvider.getClassLikeSymbolByFqName(id)!!
        }
    }

    private fun resolveToSymbol(
        typeRef: FirTypeRef,
        scopeClassDeclarations: ScopeClassDeclarations
    ): Pair<FirClassifierSymbol<*>?, ConeSubstitutor?> {
        return when (typeRef) {
            is FirResolvedTypeRef -> {
                val resultSymbol = typeRef.coneTypeSafe<ConeLookupTagBasedType>()?.lookupTag?.let(symbolProvider::getSymbolByLookupTag)
                resultSymbol to null
            }

            is FirUserTypeRef -> {
                val qualifierResolver = session.qualifierResolver
                var resolvedSymbol: FirClassifierSymbol<*>? = null
                var substitutor: ConeSubstitutor? = null
                scopeClassDeclarations.scope.processClassifiersByNameWithSubstitution(typeRef.qualifier.first().name) { symbol, substitutorFromScope ->
                    if (resolvedSymbol != null) return@processClassifiersByNameWithSubstitution
                    resolvedSymbol = when (symbol) {
                        is FirClassLikeSymbol<*> -> {
                            if (typeRef.qualifier.size == 1) {
                                symbol
                            } else {
                                qualifierResolver.resolveSymbolWithPrefix(typeRef.qualifier, symbol.classId)
                            }
                        }
                        is FirTypeParameterSymbol -> {
                            assert(typeRef.qualifier.size == 1)
                            symbol
                        }
                        else -> error("!")
                    }
                    substitutor = substitutorFromScope
                }

                // TODO: Imports
                val resultSymbol: FirClassifierSymbol<*>? = resolvedSymbol ?: qualifierResolver.resolveSymbol(typeRef.qualifier)
                resultSymbol to substitutor
            }

            is FirImplicitBuiltinTypeRef -> {
                resolveBuiltInQualified(typeRef.id, session) to null
            }

            else -> null to null
        }
    }

    private fun resolveUserType(
        typeRef: FirUserTypeRef,
        symbol: FirClassifierSymbol<*>?,
        substitutor: ConeSubstitutor?,
        areBareTypesAllowed: Boolean,
        scopeClassDeclarations: ScopeClassDeclarations
    ): ConeKotlinType {
        if (symbol == null) {
            return ConeKotlinErrorType(ConeUnresolvedQualifierError(typeRef.render()))
        }
        if (symbol is FirTypeParameterSymbol) {
            for (part in typeRef.qualifier) {
                if (part.typeArgumentList.typeArguments.isNotEmpty()) {
                    return ConeClassErrorType(
                        ConeUnexpectedTypeArgumentsError("Type arguments not allowed", part.typeArgumentList.source)
                    )
                }
            }
        }

        fun getTypeArgumentsOrNameSource(problemClass: FirRegularClass): FirSourceElement? {
            val qualifierPart = typeRef.qualifier.firstOrNull { it.name == problemClass.name }
            val typeArgumentsList = qualifierPart?.typeArgumentList
            return if (typeArgumentsList == null || typeArgumentsList.typeArguments.isEmpty()) {
                qualifierPart?.source ?: typeRef.source
            } else {
                typeArgumentsList.source
            }
        }

        val typeArguments = mutableListOf<ConeTypeProjection>()
        val typeArgumentToClassNameMap = mutableMapOf<Name, List<MatchingTypeArgument>>()
        val orderedTypeArguments = mutableListOf<MatchingTypeArgument>()

        val qualifier = typeRef.qualifier
        for (i in qualifier.size - 1 downTo 0) {
            val qualifierTypeArguments = qualifier[i].typeArgumentList.typeArguments
            if (qualifierTypeArguments.isNotEmpty()) {
                val matchingTypeArguments = mutableListOf<MatchingTypeArgument>()
                for (qualifierTypeArgument in qualifierTypeArguments) {
                    typeArguments.add(qualifierTypeArgument.toConeTypeProjection())
                    val matchingTypeArgument = MatchingTypeArgument(qualifierTypeArgument, false)
                    orderedTypeArguments.add(matchingTypeArgument)
                    matchingTypeArguments.add(matchingTypeArgument)
                }
                typeArgumentToClassNameMap[qualifier[i].name] = matchingTypeArguments
            }
        }

        if (symbol is FirRegularClassSymbol) {
            val isPossibleBareType = areBareTypesAllowed && typeArguments.isEmpty()
            if (!isPossibleBareType) {
                val actualSubstitutor = substitutor ?: ConeSubstitutor.Empty
                var errorType: TypeArgumentsErrorType? = null
                var errorClass: FirRegularClass? = null
                val topDeclaration = scopeClassDeclarations.topDeclaration
                val typeParameterToClassMap = scopeClassDeclarations.typeParameterToClassMap

                for ((index, typeParameter) in symbol.fir.typeParameters.withIndex()) {
                    val parameterClass = typeParameterToClassMap[typeParameter.symbol]

                    // Check if the argument matches a parameter
                    if (parameterClass != null) {
                        val args = typeArgumentToClassNameMap[parameterClass.name]
                        if (args != null) {
                            val localIndex = parameterClass.typeParameters.indexOfFirst { it.symbol == typeParameter.symbol }
                            if (localIndex >= args.size) {
                                if (errorType == null) {
                                    errorType = TypeArgumentsErrorType.WrongNumberOfTypeArguments
                                    errorClass = parameterClass
                                }
                            } else {
                                args[localIndex].isMatched = true
                            }
                            continue
                        }
                    } else {
                        if (index < orderedTypeArguments.size) {
                            orderedTypeArguments[index].isMatched = true
                            continue
                        }
                    }

                    if (isValidTypeParameterFromOuterClass(typeParameter, topDeclaration, session)) {
                        val type = ConeTypeParameterTypeImpl(ConeTypeParameterLookupTag(typeParameter.symbol), isNullable = false)
                        // we should report ConeSimpleDiagnostic(..., WrongNumberOfTypeArguments)
                        // but genericArgumentNumberMismatch.kt test fails with
                        // index out of bounds exception for start offset of
                        // the source
                        val substituted = actualSubstitutor.substituteOrNull(type)
                        if (substituted == null) {
                            if (errorType == null) {
                                errorType = TypeArgumentsErrorType.WrongNumberOfTypeArguments
                                errorClass = parameterClass
                            }
                        } else {
                            typeArguments.add(substituted)
                        }
                    } else {
                        if (errorType == null) {
                            errorType = TypeArgumentsErrorType.OuterClassArgumentsRequired
                            errorClass = parameterClass
                        }
                    }
                }

                if (errorType == null) {
                    // Check if any type argument not matches type parameter
                    var name: Name? = null
                    val notProcessedTypeArgument = typeArgumentToClassNameMap.firstNotNullOfOrNull {
                        name = it.key
                        it.value.firstOrNull { matchingTypeArgument -> !matchingTypeArgument.isMatched }
                    }

                    if (notProcessedTypeArgument != null && name != null) {
                        errorType = TypeArgumentsErrorType.WrongNumberOfTypeArguments
                        // Don't worry about linear complexity because it's rare case
                        errorClass = scopeClassDeclarations.allDeclarations.firstOrNull { declaration -> declaration.name == name }
                    }
                }

                if (errorType != null) {
                    val actualProblemClass = errorClass ?: symbol.fir

                    return if (errorType == TypeArgumentsErrorType.OuterClassArgumentsRequired) {
                        ConeClassErrorType(ConeOuterClassArgumentsRequired(actualProblemClass))
                    } else {
                        val actualTypeParametersCount = actualProblemClass.typeParameters.size // TODO: incorrect count
                        ConeClassErrorType(
                            ConeWrongNumberOfTypeArgumentsError(
                                actualTypeParametersCount,
                                symbol,
                                actualProblemClass,
                                getTypeArgumentsOrNameSource(actualProblemClass)
                            )
                        )
                    }
                }
            }
        }
        return symbol.constructType(typeArguments.toTypedArray(), typeRef.isMarkedNullable, typeRef.annotations.computeTypeAttributes())
            .also {
                val lookupTag = it.lookupTag
                if (lookupTag is ConeClassLikeLookupTagImpl && symbol is FirClassLikeSymbol<*>) {
                    lookupTag.bindSymbolToLookupTag(session, symbol)
                }
            }
    }

    data class MatchingTypeArgument(val typeProjection: FirTypeProjection, var isMatched: Boolean)

    enum class TypeArgumentsErrorType {
        WrongNumberOfTypeArguments,
        OuterClassArgumentsRequired
    }

    private fun createFunctionalType(typeRef: FirFunctionTypeRef): ConeClassLikeType {
        val parameters =
            listOfNotNull(typeRef.receiverTypeRef?.coneType) +
                    typeRef.valueParameters.map { it.returnTypeRef.coneType } +
                    listOf(typeRef.returnTypeRef.coneType)
        val classId = if (typeRef.isSuspend) {
            StandardNames.getSuspendFunctionClassId(typeRef.parametersCount)
        } else {
            StandardNames.getFunctionClassId(typeRef.parametersCount)
        }
        val attributes = typeRef.annotations.computeTypeAttributes()
        val symbol = resolveBuiltInQualified(classId, session)
        return ConeClassLikeTypeImpl(
            symbol.toLookupTag().also {
                if (it is ConeClassLikeLookupTagImpl) {
                    it.bindSymbolToLookupTag(session, symbol)
                }
            },
            parameters.toTypedArray(),
            typeRef.isMarkedNullable,
            attributes
        )
    }

    override fun resolveType(
        typeRef: FirTypeRef,
        scopeClassDeclarations: ScopeClassDeclarations,
        areBareTypesAllowed: Boolean
    ): ConeKotlinType {
        return when (typeRef) {
            is FirResolvedTypeRef -> typeRef.type
            is FirUserTypeRef -> {
                val (symbol, substitutor) = resolveToSymbol(typeRef, scopeClassDeclarations)
                resolveUserType(typeRef, symbol, substitutor, areBareTypesAllowed, scopeClassDeclarations)
            }
            is FirFunctionTypeRef -> createFunctionalType(typeRef)
            is FirDynamicTypeRef -> ConeKotlinErrorType(ConeUnsupportedDynamicType())
            else -> error(typeRef.render())
        }
    }
}
