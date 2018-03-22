/**
 * hub-docker-inspector
 *
 * Copyright (C) 2018 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
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
package com.blackducksoftware.integration.hub.docker.dockerinspector.dockerexec;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.blackducksoftware.integration.exception.IntegrationException;
import com.blackducksoftware.integration.hub.docker.dockerinspector.ContainerCleaner;
import com.blackducksoftware.integration.hub.docker.dockerinspector.InspectorImages;
import com.blackducksoftware.integration.hub.docker.dockerinspector.common.HubProjectName;
import com.blackducksoftware.integration.hub.docker.dockerinspector.common.Output;
import com.blackducksoftware.integration.hub.docker.dockerinspector.config.Config;
import com.blackducksoftware.integration.hub.docker.dockerinspector.config.ProgramPaths;
import com.blackducksoftware.integration.hub.docker.dockerinspector.dockerclient.DockerClientManager;
import com.blackducksoftware.integration.hub.exception.HubIntegrationException;
import com.blackducksoftware.integration.hub.imageinspector.lib.DissectedImage;
import com.blackducksoftware.integration.hub.imageinspector.lib.ImageInfoDerived;
import com.blackducksoftware.integration.hub.imageinspector.lib.ImageInspector;
import com.blackducksoftware.integration.hub.imageinspector.lib.OperatingSystemEnum;
import com.blackducksoftware.integration.hub.imageinspector.linux.FileOperations;

// TODO make this share an interface with RestClientInspector
@Component
public class DockerExecInspector {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private Config config;

    @Autowired
    private ProgramPaths programPaths;

    @Autowired
    private ImageInspector imageInspector;

    @Autowired
    private DockerClientManager dockerClientManager;

    @Autowired
    private InspectorImages dockerImages;

    @Autowired
    private HubProjectName hubProjectName;

    @Autowired
    private Output output;

    public int getBdio(final DissectedImage dissectedImage) throws IOException, IntegrationException, InterruptedException, CompressorException, IllegalAccessException {
        int returnCode;
        determineTargetOsFromContainerFileSystem(config, dissectedImage);
        final Future<String> deferredCleanup = inspect(config, dissectedImage);
        output.uploadBdio(config, dissectedImage);
        provideDockerTar(config, dissectedImage.getDockerTarFile());
        output.provideOutput(config);
        returnCode = output.reportResults(config, dissectedImage);
        output.cleanUp(config, deferredCleanup);
        return returnCode;
    }

    private Future<String> inspect(final Config config, final DissectedImage dissectedImage) throws IOException, InterruptedException, CompressorException, IllegalAccessException, IntegrationException {
        Future<String> deferredCleanup = null;
        if (config.isOnHost()) {
            logger.info("Inspecting image in container");
            deferredCleanup = inspectInSubContainer(config, dissectedImage.getDockerTarFile(), dissectedImage.getTargetOs(), dissectedImage.getRunOnImageName(), dissectedImage.getRunOnImageTag());
        } else {
            if (dissectedImage.getTargetImageFileSystemRootDir() == null) {
                dissectedImage.setTargetImageFileSystemRootDir(
                        imageInspector.extractDockerLayers(new File(programPaths.getHubDockerWorkingDirPath()), config.getDockerImageRepo(), config.getDockerImageTag(), dissectedImage.getLayerTars(), dissectedImage.getLayerMappings()));
            }
            if (dissectedImage.getTargetOs() == null) {
                dissectedImage.setTargetOs(imageInspector.detectOperatingSystem(dissectedImage.getTargetImageFileSystemRootDir()));
            }
            logger.info(String.format("Target image tarfile: %s; target OS: %s", dissectedImage.getDockerTarFile().getAbsolutePath(), dissectedImage.getTargetOs().toString()));
            final ImageInfoDerived imageInfoDerived = imageInspector.generateBdioFromImageFilesDir(config.getDockerImageRepo(), config.getDockerImageTag(), dissectedImage.getLayerMappings(), hubProjectName.getHubProjectName(config),
                    hubProjectName.getHubProjectVersion(config), dissectedImage.getDockerTarFile(), dissectedImage.getTargetImageFileSystemRootDir(), dissectedImage.getTargetOs(), config.getHubCodelocationPrefix());
            output.writeBdioFile(dissectedImage, imageInfoDerived);
            output.createContainerFileSystemTarIfRequested(config, dissectedImage.getTargetImageFileSystemRootDir());
        }
        return deferredCleanup;
    }

    private Future<String> inspectInSubContainer(final Config config, final File dockerTarFile, final OperatingSystemEnum targetOs, final String runOnImageName, final String runOnImageTag)
            throws InterruptedException, IOException, IllegalArgumentException, IllegalAccessException, IntegrationException {
        final String msg = String.format("Image inspection for %s will use docker image %s:%s", targetOs.toString(), runOnImageName, runOnImageTag);
        logger.info(msg);
        String runOnImageId = null;
        try {
            runOnImageId = dockerClientManager.pullImage(runOnImageName, runOnImageTag);
        } catch (final Exception e) {
            logger.warn(String.format("Unable to pull docker image %s:%s; proceeding anyway since it may already exist locally", runOnImageName, runOnImageTag));
        }
        logger.debug(String.format("runInSubContainer(): Running subcontainer on image %s, repo %s, tag %s", config.getDockerImage(), config.getDockerImageRepo(), config.getDockerImageTag()));
        final String containerId = dockerClientManager.run(runOnImageName, runOnImageTag, runOnImageId, dockerTarFile, true, config.getDockerImage(), config.getDockerImageRepo(), config.getDockerImageTag());

        // spin the inspector container/image cleanup off in it's own parallel thread
        final ContainerCleaner containerCleaner = new ContainerCleaner(dockerClientManager, runOnImageId, containerId, config.isCleanupInspectorContainer(), config.isCleanupInspectorImage());
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<String> containerCleanerFuture = executor.submit(containerCleaner);
        return containerCleanerFuture;

    }

    private void provideDockerTar(final Config config, final File dockerTarFile) throws IOException {
        if (config.isOutputIncludeDockertarfile()) {
            if (config.isOnHost()) {
                final File outputDirectory = new File(programPaths.getHubDockerOutputPathHost());
                logger.debug(String.format("Copying %s to output dir %s", dockerTarFile.getAbsolutePath(), outputDirectory.getAbsolutePath()));
                FileOperations.copyFile(dockerTarFile, outputDirectory);
            } else {
                final File outputDirectory = new File(programPaths.getHubDockerOutputPathContainer());
                logger.debug(String.format("Moving %s to output dir %s", dockerTarFile.getAbsolutePath(), outputDirectory.getAbsolutePath()));
                FileOperations.moveFile(dockerTarFile, outputDirectory);
            }
        }
    }

    private void determineTargetOsFromContainerFileSystem(final Config config, final DissectedImage dissectedImage) throws IOException, IntegrationException {
        if (dissectedImage.getTargetOs() == null) {
            dissectedImage.setTargetOs(imageInspector.detectOperatingSystem(dissectedImage.getTargetImageFileSystemRootDir()));
        }
        dissectedImage.setRunOnImageName(dockerImages.getInspectorImageName(dissectedImage.getTargetOs()));
        dissectedImage.setRunOnImageTag(dockerImages.getInspectorImageTag(dissectedImage.getTargetOs()));
        logger.info(String.format("Identified target OS: %s; corresponding inspection image: %s:%s", dissectedImage.getTargetOs().name(), dissectedImage.getRunOnImageName(), dissectedImage.getRunOnImageTag()));
        if (StringUtils.isBlank(dissectedImage.getRunOnImageName()) || StringUtils.isBlank(dissectedImage.getRunOnImageTag())) {
            throw new HubIntegrationException("Failed to identify inspection image name and/or tag");
        }
    }

}
