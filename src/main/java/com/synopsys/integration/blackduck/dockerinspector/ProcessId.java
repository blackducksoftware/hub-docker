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
package com.synopsys.integration.blackduck.dockerinspector;

import java.lang.management.ManagementFactory;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProcessId {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());
  private final String cleanedProcessId;


  public ProcessId() {
    cleanedProcessId = atSignToUnderscore(getProcessIdOrGenerateUniqueId());
    logger.debug(String.format("Process name: %s", cleanedProcessId));
  }

  public String addProcessIdToName(final String origName) {
    final String adjustedName = String.format("%s_%s", origName, cleanedProcessId);
    logger.debug(String.format("Adjusted %s to %s", origName, adjustedName));
    return adjustedName;
  }

  private String getProcessIdOrGenerateUniqueId() {
    String processId;
    try {
      processId = ManagementFactory.getRuntimeMXBean().getName();
      return processId;
    } catch (final Exception e) {
      logger.debug(String.format("Unable to get process ID from system: %s", e.getMessage()));
      final long currentMillisecond = new Date().getTime();
      processId = Long.toString(currentMillisecond);
    }
    return processId;
  }

  private String atSignToUnderscore(final String imageName) {
    return imageName.replace("@", "_");
  }
}
