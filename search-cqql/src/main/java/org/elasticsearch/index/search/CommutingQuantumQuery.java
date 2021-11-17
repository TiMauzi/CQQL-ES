/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.search;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Objects;
import java.util.Queue;

/**
 * A query that applies the rules of quantum mechanics by using the commuting quantum query language (CQQL).
 * The implementation follows the concepts described in
 * <a href="https://doi.org/10.1007/s00778-007-0070-1">
 *   Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, pp. 39-56 (2008).
 * </a><br>
 * An object of this class is usually prepared and instantiated by {@link org.elasticsearch.index.query.CommutingQuantumQueryBuilder}.
 */
public class CommutingQuantumQuery extends Query {
    private String queryString;
    private String calcString;
    private Query[] conditions;
    private String[] conditionStrings;
    private int hashCode;

    /**
     * Regular constructor, using a given logical formula to evaluate the query.
     * @param formula a logical formula implying the query
     * @param literals the different literals contained in the formula
     * @param queries the different child queries implied by the literals
     */
    public CommutingQuantumQuery(String formula, String[] literals, Query[] queries) {
        this.queryString = formula;
        this.calcString = queryToCalc(formula);
        this.conditions = queries;
        this.conditionStrings = literals;
    }

    /**
     * Creates a calculation calculation formula with respect to a specific logical formula. The rules for that follow the concept of <a href="https://doi.org/10.1007/s00778-007-0070-1">
     *   Schmitt, I. QQL: A DB&amp;IR Query Language. The VLDB Journal 17, p. 49 (2008).
     * </a><br>
     * This method contains a privileged part due to the usage of <a href="https://github.com/axkr/symja_android_library/">Symja</a>.
     * @param queryString the logical formula {@link String} in {@link IExpr}-like format
     * @return the calculation formula {@link String} in {@link IExpr}-like format
     */
    private String queryToCalc(String queryString) {
        // replace all leftover True and False values by 1 and 0
        String calcString = queryString
            .replaceAll("False","0")
            .replaceAll("True", "1");
        // replace all &, ||, and !... by *, +, and (1-...)
        return (String) AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    ExprEvaluator calcEvaluator = new ExprEvaluator(false, (short) 100);
                    calcEvaluator.eval("evalDisConNeg = {" +
                        "x_ || y_ :> x + y - (x * y)," +
                        "x_ && y_ :> x * y," +
                        "!(x_) :> (1-x)}"
                    );
                    IExpr calc = calcEvaluator.eval("ReplaceRepeated(" + calcString + ", evalDisConNeg)");
                    //System.out.println(calc.toString());
                    return calc.toString();
                } catch (SyntaxError se) {
                    // catch Symja parser errors here
                    System.out.println(se.getMessage());
                } catch (MathException me) {
                    // catch Symja math errors here
                    System.out.println(me.getMessage());
                } catch (final Exception ex) {
                    System.out.println(ex.getMessage());
                } catch (final StackOverflowError soe) {
                    System.out.println(soe.getMessage());
                } catch (final OutOfMemoryError oome) {
                    System.out.println(oome.getMessage());
                }
                return "";
            }
        });
    }

    @Override
    public String toString(String field) {
        return queryString; // TODO find prettier output maybe?
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        // TODO custom similarity could go here
        return new CommutingQuantumWeight(this, searcher, scoreMode, boost);
    }

    @Override
    public boolean equals(Object obj) {
        try {
            ExprEvaluator eqEvaluator = new ExprEvaluator(false, (short) 100);
            if (sameClassAs(obj)) {
                // Usage of complement law
                IExpr isEqual = eqEvaluator.eval("BooleanMinimize((" + this.queryString + ")||!(" + ((CommutingQuantumQuery) obj).getQueryString() + "))");
                if (isEqual.toString().equals("True")) {
                    return true;
                }
            }
        } catch (SyntaxError se) {
            // catch Symja parser errors here
            System.out.println(se.getMessage());
        } catch (MathException me) {
            // catch Symja math errors here
            System.out.println(me.getMessage());
        } catch (final Exception ex) {
            System.out.println(ex.getMessage());
        } catch (final StackOverflowError soe) {
            System.out.println(soe.getMessage());
        } catch (final OutOfMemoryError oome) {
            System.out.println(oome.getMessage());
        }
        return false;
    }

    /**
     * Computes a hash code out of a {@code queryString} (cf. {@link BooleanQuery}}.
     * @return a hash code
     */
    private int computeHashCode() {
        int hashCode = Objects.hash(queryString);
        if (hashCode == 0) {
            hashCode = 1;
        }
        return hashCode;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = computeHashCode();
        }
        assert hashCode == computeHashCode();
        return hashCode;
    }

    public String getQueryString() {
        return queryString;
    }

    public String getCalcString() {
        return calcString;
    }

    public Query[] getConditions() {
        return conditions;
    }

    public String[] getConditionStrings() {
        return conditionStrings;
    }

}
