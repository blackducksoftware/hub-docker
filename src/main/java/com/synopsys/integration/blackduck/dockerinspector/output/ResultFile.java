/**
 * blackduck-docker-inspector
 *
 * Copyright (c) 2021 Synopsys, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.synopsys.integration.blackduck.dockerinspector.output;

import com.google.gson.Gson;
import com.synopsys.integration.blackduck.imageinspector.api.ImageInspectorOsEnum;
import java.io.File;
import java.io.FileOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResultFile {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public void write(final Gson gson, final File resultsOutputFile, final Result result) {
        try {
            logger.trace(String.format("Writing resultsOutputFile: %s; result: %s", resultsOutputFile.getAbsolutePath(), result.toString()));
            resultsOutputFile.getParentFile().mkdirs();
            try (FileOutputStream resultOutputStream = new FileOutputStream(resultsOutputFile)) {
                try (ResultWriter resultWriter = new ResultWriter(gson, resultOutputStream)) {
                    resultWriter.writeResult(result);
                }
            }
        } catch (final Exception e) {
            logger.error(String.format("Error writing output file: %s", e.getMessage()));
        }
    }
}
