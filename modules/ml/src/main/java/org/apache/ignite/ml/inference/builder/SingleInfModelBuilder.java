/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.inference.builder;

import java.io.Serializable;
import org.apache.ignite.ml.inference.InfModel;
import org.apache.ignite.ml.inference.parser.InfModelParser;
import org.apache.ignite.ml.inference.reader.InfModelReader;

/**
 * Implementation of synchronous inference model builder that builds a model processed locally in a single thread.
 */
public class SingleInfModelBuilder implements SyncInfModelBuilder {
    /** {@inheritDoc} */
    @Override public <I extends Serializable, O extends Serializable, M extends InfModel<I, O>> M build(InfModelReader reader,
        InfModelParser<I, O, M> parser) {
        return parser.parse(reader.read());
    }
}