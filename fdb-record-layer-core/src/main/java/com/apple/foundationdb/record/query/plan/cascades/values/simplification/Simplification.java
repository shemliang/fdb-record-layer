/*
 * Simplification.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.cascades.values.simplification;

import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.LinkedIdentityMap;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.apple.foundationdb.record.query.plan.cascades.values.simplification.ValueComputationRuleCall.ValueWithResult;
import com.google.common.base.Verify;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Main class of a mini rewrite engine to simplify (or to compute over)
 * {@link com.apple.foundationdb.record.query.plan.cascades.values.Value} trees.
 */
public class Simplification {
    /**
     * Main function that simplifies the given value using the {@link AbstractValueRuleSet} passed in.
     * @param root the {@link Value} to be simplified
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified {@link Value} of {@code root}
     */
    @Nonnull
    public static Value simplify(@Nonnull final Value root,
                                 @Nonnull final AliasMap aliasMap,
                                 @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                 @Nonnull final AbstractValueRuleSet<Value, ValueSimplificationRuleCall> ruleSet) {
        //
        // The general strategy is to invoke the rule engine bottom up in post-fix order of the values in the value tree.
        // For each node, all rules are exhaustively applied until no rules can make progress anymore. We avoid creating
        // duplicate subtrees by detecting changes made to children using object identity.
        //

        //
        // Use mapMaybe() to apply a lambda in post-fix bottom up fashion.
        //
        return root.<Value>mapMaybe((current, mappedChildren) -> {
            //
            // If any of the children have changed as compared to the actual children of current, we need to recreate
            // current. We call computeCurrent() to do that.
            //
            current = computeCurrent(current, mappedChildren);

            //
            // Run the entire given rule set for current.
            //
            return executeRuleSet(root,
                    current,
                    ruleSet,
                    (rule, r, c, plannerBindings) -> new ValueSimplificationRuleCall(rule, r, c, plannerBindings, aliasMap, constantAliases),
                    Iterables::getOnlyElement);
        }).orElseThrow(() -> new RecordCoreException("expected a mapped tree"));
    }

    /**
     * Main function that simplifies the given value using the {@link AbstractValueRuleSet} passed in. In addition to
     * the regular {@link #simplify(Value, AliasMap, Set, AbstractValueRuleSet)}, this method uses a computation rule set
     * that is passed in to derive useful information from the value tree. In particular, this is currently used to
     * track matches of subtrees and their compensation.
     * @param <A> type parameter of the argument
     * @param <R> type parameter of the result
     * @param root the {@link Value} to be simplified
     * @param argument argument to the computations (of type {@code R})
     * @param aliasMap an alias map of equalities
     * @param constantAliases a set of aliases that are considered to be constant
     * @param ruleSet the computation rule set used to simplify the {@link Value} that is passed in
     * @return a new simplified {@link ValueWithResult} which contains the computation result as well as a simplified
     *         {@code root}.
     */
    @Nullable
    public static <A, R> ValueWithResult<R> compute(@Nonnull final Value root,
                                                    @Nonnull final A argument,
                                                    @Nonnull final AliasMap aliasMap,
                                                    @Nonnull final Set<CorrelationIdentifier> constantAliases,
                                                    @Nonnull final ValueComputationRuleSet<A, R> ruleSet) {
        //
        // The general strategy is to invoke the rule engine bottom up in post-fix order of the values in the value tree.
        // For each node, all rules are exhaustively applied until no rules can make progress anymore. We avoid creating
        // duplicate subtrees by detecting changes made to children using object identity.
        //

        //
        // Computation results are returned by individual rules and kept in a results map. This map is heavily modified
        // by rules matching and executing on a given Value for that value.
        //
        final var resultsMap = new LinkedIdentityMap<Value, ValueWithResult<R>>();
        final var newRoot = root.<Value>mapMaybe((current, mappedChildren) -> {
            //
            // If any of the children have changed as compared to the actual children of current, we need to recreate
            // current. We call computeCurrent() to do that.
            //
            current = computeCurrent(current, mappedChildren);

            //
            // Run the entire given rule set for current.
            //
            return executeRuleSet(root,
                    current,
                    ruleSet,
                    (rule, r, c, plannerBindings) -> new ValueComputationRuleCall<>(rule, r, c, argument, plannerBindings, aliasMap, constantAliases, resultsMap::get),
                    results -> onResultsFunction(resultsMap, results));
        }).orElseThrow(() -> new RecordCoreException("expected a mapped tree"));
        return resultsMap.get(newRoot);
    }

    /**
     * Compute a new current value if necessary, that is, if any of the children passed in are different when compared
     * to the actual children of the current value passed in.
     * @param current the current value
     * @param mappedChildren the mapped children, which may or may not be different from the actual children of the
     *                       current value
     * @return the current value that was passed in by the caller if all mapped children are identical to the actual
     *         children of the current value or a new current value that was creating by calling
     *         {@link Value#withChildren(Iterable)}
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static Value computeCurrent(@Nonnull final Value current, @Nonnull final Iterable<? extends Value> mappedChildren) {
        final var children = current.getChildren();
        final var childrenIterator = children.iterator();
        final var mappedChildrenIterator = mappedChildren.iterator();
        boolean isSame = true;
        while (childrenIterator.hasNext() && mappedChildrenIterator.hasNext()) {
            final Value child = childrenIterator.next();
            final Value mappedChild = mappedChildrenIterator.next();
            if (child != mappedChild) {
                isSame = false;
                break;
            }
        }
        // make sure they are both exhausted or both are not exhausted
        Verify.verify(childrenIterator.hasNext() == mappedChildrenIterator.hasNext());
        return isSame ? current : current.withChildren(mappedChildren);
    }

    @Nonnull
    private static <R> Value onResultsFunction(@Nonnull final Map<Value, ValueWithResult<R>> resultsMap,
                                               @Nonnull final Collection<ValueWithResult<R>> results) {
        Verify.verify(results.size() <= 1);

        final var valueWithResult = Iterables.getOnlyElement(results);
        final var value = valueWithResult.getValue();
        resultsMap.put(value, valueWithResult);
        return value;
    }

    /**
     * Execute a set of rules on the current {@link Value}. This method assumes that all children of the current value
     * have already been simplified, that is, the rules set has already been exhaustively applied to the entire subtree
     * underneath the current value. In contrast to {@link com.apple.foundationdb.record.query.plan.cascades.CascadesPlanner}
     * which creates new variations for yielded new expressions, the logic in this method applies the rule set in a
     * destructive manner meaning that the last yield wins and all previous yields on the current values were merely
     * interim stepping stones in transforming the original value to the final value. Thus, the order of the rules in
     * the rule set is important.
     * @param <R> type parameter for results
     * @param <C> type parameter for the rule call object to be used
     * @param root the root value of the simplification/computation. This information is needed for some rules as
     *             they may only fire if {@code current} is/is not the root.
     * @param current the current value that the rule set should be executed on
     * @param ruleSet the rule set
     * @param ruleCallCreator a function that creates an instance of {@code C} which is some derivative of
     *        {@link AbstractValueRuleCall}
     * @param onResultsFunction a function that is called to manage and unwrap a computational result of a yield. This
     *                          function is trivial for simplifications.
     * @return a resulting {@link Value} after all rules in the rule set have been exhaustively applied
     */
    @Nonnull
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static <R, C extends AbstractValueRuleCall<R, C>> Value executeRuleSet(@Nonnull final Value root,
                                                                                   @Nonnull Value current,
                                                                                   @Nonnull final AbstractValueRuleSet<R, C> ruleSet,
                                                                                   @Nonnull final RuleCallCreator<R, C> ruleCallCreator,
                                                                                   @Nonnull final Function<Collection<R>, Value> onResultsFunction) {
        final boolean isRoot = current == root;
        Value newCurrent = current;
        do {
            current = newCurrent;
            final var ruleIterator =
                    ruleSet.getValueRules(current).iterator();

            while (ruleIterator.hasNext()) {
                final var rule = ruleIterator.next();
                final BindingMatcher<? extends Value> matcher = rule.getMatcher();

                final var matchIterator = matcher.bindMatches(PlannerBindings.empty(), current).iterator();

                while (matchIterator.hasNext()) {
                    final var plannerBindings = matchIterator.next();
                    final var ruleCall = ruleCallCreator.create(rule, isRoot ? current : root, current, plannerBindings);

                    //
                    // Run the rule. See if the rule yielded a simplification.
                    //
                    rule.onMatch(ruleCall);
                    final var results = ruleCall.getResults();

                    if (!results.isEmpty()) {
                        newCurrent = onResultsFunction.apply(results);

                        if (current != newCurrent) {
                            //
                            // We made progress. Make sure we exit the inner while loops and restart with the first rule
                            // for the new `current` again.
                            //
                            break;
                        }
                    }
                }

                if (current != newCurrent) {
                    break;
                }
            }
        } while (current != newCurrent);

        return current;
    }

    /**
     * Functional interface to create a specific rule call object.
     * @param <R> the type parameter representing the type of result that is handed to {@link PlannerRuleCall#yield(Object)}
     * @param <C> the type parameter extending {@link AbstractValueRuleCall}
     */
    @FunctionalInterface
    public interface RuleCallCreator<R, C extends AbstractValueRuleCall<R, C>> {
        C create(@Nonnull AbstractValueRule<R, C, ? extends Value> rule,
                 @Nonnull Value root,
                 @Nonnull Value current,
                 @Nonnull PlannerBindings plannerBindings);
    }
}

