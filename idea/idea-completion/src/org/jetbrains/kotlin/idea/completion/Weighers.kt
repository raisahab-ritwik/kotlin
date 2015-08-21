/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.codeInsight.lookup.WeighingContext
import com.intellij.openapi.module.ModuleUtilCore
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.idea.completion.smart.*
import org.jetbrains.kotlin.idea.core.ImportableFqNameClassifier
import org.jetbrains.kotlin.idea.core.completion.DeclarationLookupObject
import org.jetbrains.kotlin.idea.util.FuzzyType
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.kotlin.types.typeUtil.isBooleanOrNullableBoolean
import org.jetbrains.kotlin.types.typeUtil.nullability

object PriorityWeigher : LookupElementWeigher("kotlin.priority") {
    override fun weigh(element: LookupElement, context: WeighingContext)
            = element.getUserData(ITEM_PRIORITY_KEY) ?: ItemPriority.DEFAULT
}

class NotImportedWeigher(private val classifier: ImportableFqNameClassifier) : LookupElementWeigher("kotlin.notImported") {
    private enum class Weight {
        default,
        hasImportFromSamePackage,
        notImported,
        notToBeUsedInKotlin
    }

    override fun weigh(element: LookupElement): Weight {
        if (element.getUserData(NOT_IMPORTED_KEY) == null) return Weight.default
        val o = element.`object` as? DeclarationLookupObject
        val fqName = o?.importableFqName ?: return Weight.default
        return when (classifier.classify(fqName, o is PackageLookupObject)) {
            ImportableFqNameClassifier.Classification.hasImportFromSamePackage -> Weight.hasImportFromSamePackage
            ImportableFqNameClassifier.Classification.notImported -> Weight.notImported
            ImportableFqNameClassifier.Classification.notToBeUsedInKotlin -> Weight.notToBeUsedInKotlin
            else -> Weight.default
        }
    }
}

class ImportedWeigher(private val classifier: ImportableFqNameClassifier) : LookupElementWeigher("kotlin.imported") {
    private enum class Weight {
        currentPackage,
        defaultImport,
        preciseImport,
        allUnderImport
    }

    override fun weigh(element: LookupElement): Weight? {
        val o = element.`object` as? DeclarationLookupObject
        val fqName = o?.importableFqName ?: return null
        return when (classifier.classify(fqName, o is PackageLookupObject)) {
            ImportableFqNameClassifier.Classification.fromCurrentPackage -> Weight.currentPackage
            ImportableFqNameClassifier.Classification.defaultImport -> Weight.defaultImport
            ImportableFqNameClassifier.Classification.preciseImport -> Weight.preciseImport
            ImportableFqNameClassifier.Classification.allUnderImport -> Weight.allUnderImport
            else -> null
        }
    }
}

class LocationWeigher(private val file: JetFile, private val originalFile: JetFile) : LookupElementWeigher("kotlin.location") {
    private val currentModule = ModuleUtilCore.findModuleForPsiElement(originalFile)

    private enum class Weight {
        currentFile,
        currentModule,
        project,
        libraries
    }

    override fun weigh(element: LookupElement): Weight? {
        val declaration = (element.`object` as? DeclarationLookupObject)?.psiElement ?: return null
        return when {
            declaration.containingFile == file -> Weight.currentFile
            ModuleUtilCore.findModuleForPsiElement(declaration) == currentModule -> Weight.currentModule
            ProjectRootsUtil.isInProjectSource(declaration) -> Weight.project
            else -> Weight.libraries
        }
    }
}

object SmartCompletionPriorityWeigher : LookupElementWeigher("kotlin.smartCompletionPriority") {
    override fun weigh(element: LookupElement, context: WeighingContext)
            = element.getUserData(SMART_COMPLETION_ITEM_PRIORITY_KEY) ?: SmartCompletionItemPriority.DEFAULT
}

object KindWeigher : LookupElementWeigher("kotlin.kind") {
    private enum class Weight {
        enumMember,
        callable,
        keyword,
        default,
        packages
    }

    override fun weigh(element: LookupElement): Weight {
        val o = element.getObject()

        return when (o) {
            is PackageLookupObject -> Weight.packages

            is DeclarationLookupObject -> {
                val descriptor = o.descriptor
                when (descriptor) {
                    is VariableDescriptor, is FunctionDescriptor -> Weight.callable
                    is ClassDescriptor -> if (descriptor.kind == ClassKind.ENUM_ENTRY) Weight.enumMember else Weight.default
                    else -> Weight.default
                }
            }

            is KeywordLookupObject -> Weight.keyword

            else -> Weight.default
        }
    }
}

object CallableWeigher : LookupElementWeigher("kotlin.callableWeight") {
    override fun weigh(element: LookupElement) = element.getUserData(CALLABLE_WEIGHT_KEY)
}

object VariableOrFunctionWeigher : LookupElementWeigher("kotlin.variableOrFunction"){
    private enum class Weight {
        variable,
        function
    }

    override fun weigh(element: LookupElement): Weight? {
        val descriptor = (element.`object` as? DeclarationLookupObject)?.descriptor ?: return null
        return when (descriptor) {
            is VariableDescriptor -> Weight.variable
            is FunctionDescriptor -> Weight.function
            else -> null
        }
    }
}

object DeprecatedWeigher : LookupElementWeigher("kotlin.deprecated") {
    override fun weigh(element: LookupElement): Int {
        val o = element.getObject() as? DeclarationLookupObject ?: return 0
        return if (o.isDeprecated) 1 else 0
    }
}

object PreferMatchingItemWeigher : LookupElementWeigher("kotlin.preferMatching", false, true) {
    private enum class Weight {
        keywordExactMatch,
        defaultExactMatch,
        functionExactMatch,
        notExactMatch
    }

    override fun weigh(element: LookupElement, context: WeighingContext): Weight {
        val prefix = context.itemPattern(element)
        if (element.lookupString != prefix) {
            return Weight.notExactMatch
        }
        else {
            val o = element.`object`
            return when (o) {
                is KeywordLookupObject -> Weight.keywordExactMatch
                is DeclarationLookupObject -> if (o.descriptor is FunctionDescriptor) Weight.functionExactMatch else Weight.defaultExactMatch
                else -> Weight.defaultExactMatch
            }
        }
    }
}

class SmartCompletionInBasicWeigher(private val smartCompletion: SmartCompletion) : LookupElementWeigher("kotlin.smartInBasic", true, false) {
    private val descriptorsToSkip = smartCompletion.descriptorsToSkip
    private val expectedInfos = smartCompletion.expectedInfos

    private fun fullMatchWeight(nameSimilarity: Int) = (3L shl 32) + nameSimilarity * 3 // true and false should be in between zero-nameSimilarity and 1-nameSimilarity

    private val MATCHED_TRUE_WEIGHT = (3L shl 32) + 2
    private val MATCHED_FALSE_WEIGHT = (3L shl 32) + 1

    private fun ifNotNullMatchWeight(nameSimilarity: Int) = (2L shl 32) + nameSimilarity

    private fun smartCompletionItemWeight(nameSimilarity: Int) = (1L shl 32) + nameSimilarity

    private val MATCHED_NULL_WEIGHT = 1L

    private val NO_MATCH_WEIGHT = 0L

    private val DESCRIPTOR_TO_SKIP_WEIGHT = -1L // if descriptor is skipped from smart completion then it's probably irrelevant

    override fun weigh(element: LookupElement): Long {
        val smartCompletionPriority = element.getUserData(SMART_COMPLETION_ITEM_PRIORITY_KEY)
        if (smartCompletionPriority != null) { // it's an "additional item" came from smart completion, don't match it against expected type
            return smartCompletionItemWeight(element.getUserData(NAME_SIMILARITY_KEY) ?: 0)
        }

        val o = element.`object`

        if ((o as? DeclarationLookupObject)?.descriptor in descriptorsToSkip) return DESCRIPTOR_TO_SKIP_WEIGHT

        if (expectedInfos.isEmpty()) return NO_MATCH_WEIGHT

        if (o is KeywordLookupObject) {
            when (element.lookupString) {
                "true", "false" -> {
                    if (expectedInfos.any { it.fuzzyType?.type?.isBooleanOrNullableBoolean() ?: false }) {
                        return if (element.lookupString == "true") MATCHED_TRUE_WEIGHT else MATCHED_FALSE_WEIGHT
                    }
                    else {
                        return NO_MATCH_WEIGHT
                    }
                }

                "null" -> {
                    if (expectedInfos.any { it.fuzzyType?.type?.nullability()?.let { it != TypeNullability.NOT_NULL } ?: false }) {
                        return MATCHED_NULL_WEIGHT
                    }
                    else {
                        return NO_MATCH_WEIGHT
                    }
                }
            }
        }

        val smartCastCalculator = smartCompletion.smartCastCalculator

        val (fuzzyTypes, name) = when (o) {
            is DeclarationLookupObject -> {
                val descriptor = o.descriptor ?: return NO_MATCH_WEIGHT
                descriptor.fuzzyTypesForSmartCompletion(smartCastCalculator) to descriptor.name
            }

            is ThisItemLookupObject -> smartCastCalculator.types(o.receiverParameter).map { FuzzyType(it, emptyList()) } to null

            else -> return NO_MATCH_WEIGHT
        }

        if (fuzzyTypes.isEmpty()) return NO_MATCH_WEIGHT

        val classified: Collection<Pair<ExpectedInfo, ExpectedInfoClassification>> = expectedInfos.map { it to fuzzyTypes.classifyExpectedInfo(it) }
        if (classified.all { it.second == ExpectedInfoClassification.noMatch }) return NO_MATCH_WEIGHT

        val nameSimilarity = if (name != null) {
            val matchingInfos = classified.filter { it.second != ExpectedInfoClassification.noMatch }.map { it.first }
            calcNameSimilarity(name.asString(), matchingInfos)
        }
        else {
            0
        }

        return if (classified.any { it.second.isMatch() })
            fullMatchWeight(nameSimilarity)
        else
            ifNotNullMatchWeight(nameSimilarity)
    }
}