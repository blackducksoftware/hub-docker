/**
 * Hub Docker Inspector
 *
 * Copyright (C) 2017 Black Duck Software, Inc.
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
package com.blackducksoftware.integration.hub.docker;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.blackducksoftware.integration.hub.bdio.simple.BdioWriter;
import com.blackducksoftware.integration.hub.docker.client.DockerClientManager;
import com.blackducksoftware.integration.hub.docker.client.ProgramPaths;
import com.blackducksoftware.integration.hub.docker.extractor.ExtractionDetails;
import com.blackducksoftware.integration.hub.docker.extractor.Extractor;
import com.blackducksoftware.integration.hub.docker.linux.Dirs;
import com.blackducksoftware.integration.hub.docker.linux.EtcDir;
import com.blackducksoftware.integration.hub.docker.tar.DockerTarParser;
import com.blackducksoftware.integration.hub.docker.tar.ImageInfo;
import com.blackducksoftware.integration.hub.docker.tar.manifest.ManifestLayerMapping;
import com.blackducksoftware.integration.hub.exception.HubIntegrationException;
import com.google.gson.Gson;

@Component
public class HubDockerManager {
    private final Logger logger = LoggerFactory.getLogger(HubDockerManager.class);

    @Value("${linux.distro}")
    String linuxDistro;

    @Autowired
    HubClient hubClient;

    @Autowired
    ProgramPaths programPaths;

    @Autowired
    DockerClientManager dockerClientManager;

    @Autowired
    List<Extractor> extractors;

    @Autowired
    DockerTarParser tarParser;

    @Autowired
    PackageManagerFiles packageManagerFiles;

    void init() {
        tarParser.setWorkingDirectory(new File(programPaths.getHubDockerWorkingDirPath()));
    }

    File getTarFileFromDockerImage(final String imageName, final String tagName) {
        return dockerClientManager.getTarFileFromDockerImage(imageName, tagName);
    }

    List<File> extractLayerTars(final File dockerTar) throws IOException {
        return tarParser.extractLayerTars(dockerTar);
    }

    File extractDockerLayers(final List<File> layerTars, final List<ManifestLayerMapping> layerMappings) throws IOException {
        return tarParser.extractDockerLayers(layerTars, layerMappings);
    }

    // String extractManifestFileContent(final String dockerTarName) {
    // return tarParser.extractManifestFileContent(dockerTarName);
    // }
    // TODO: remove above? make method public or private
    // TODO exception handling
    OperatingSystemEnum detectOperatingSystem(final String operatingSystem, final File targetImageFileSystemRootDir) throws HubIntegrationException, IOException {
        return tarParser.detectOperatingSystem(operatingSystem, targetImageFileSystemRootDir);
    }

    OperatingSystemEnum detectCurrentOperatingSystem() throws HubIntegrationException, IOException {
        final EtcDir etcDir = new EtcDir(new File("/etc"));
        return etcDir.getOperatingSystem();
    }

    List<ManifestLayerMapping> getLayerMappings(final String tarFileName, final String dockerImageName, final String dockerTagName) throws Exception {
        return tarParser.getLayerMappings(tarFileName, dockerImageName, dockerTagName);
    }

    // TODO: fix groovy syntax strings
    List<File> generateBdioFromImageFilesDir(final List<ManifestLayerMapping> mappings, final String projectName, final String versionName, final File dockerTar, final File targetImageFileSystemRootDir, final OperatingSystemEnum osEnum)
            throws IOException, HubIntegrationException, InterruptedException {
        final ImageInfo imagePkgMgrInfo = tarParser.collectPkgMgrInfo(targetImageFileSystemRootDir, osEnum);
        if (imagePkgMgrInfo.getOperatingSystemEnum() == null) {
            throw new HubIntegrationException("Could not determine the Operating System of this Docker tar.");
        }
        String architecture = null;
        if (osEnum == OperatingSystemEnum.ALPINE) {
            final List<File> etcDirectories = Dirs.findFileWithName(targetImageFileSystemRootDir, "etc");
            for (final File etc : etcDirectories) {
                File architectureFile = new File(etc, "apk");
                architectureFile = new File(architectureFile, "arch");
                if (architectureFile.exists()) {
                    architecture = FileUtils.readLines(architectureFile, "UTF-8").get(0);
                    break;
                }
            }
        }
        return generateBdioFromPackageMgrDirs(mappings, projectName, versionName, dockerTar.getName(), imagePkgMgrInfo, architecture);
    }

    void uploadBdioFiles(final List<File> bdioFiles) {
        if (hubClient.isValid()) {
            if (bdioFiles != null) {
                for (final File file : bdioFiles) {
                    hubClient.uploadBdioToHub(file);
                }
            }
            logger.info(" ");
            logger.info("Successfully uploaded all of the bdio files!");
            logger.info(" ");
        }
    }

    void cleanWorkingDirectory() throws IOException {
        final File workingDirectory = new File(programPaths.getHubDockerWorkingDirPath());
        if (workingDirectory.exists()) {
            FileUtils.deleteDirectory(workingDirectory);
        }
    }

    // TODO move this to a more logical place (like maybe Dir?)
    void copyFile(final File fileToCopy, final File destination) throws IOException {
        final String filename = fileToCopy.getName();
        logger.debug("Copying ${fileToCopy.getAbsolutePath()} to ${destination.getAbsolutePath()}");
        final Path destPath = destination.toPath().resolve(filename);
        Files.copy(fileToCopy.toPath(), destPath);
    }

    private List<File> generateBdioFromPackageMgrDirs(final List<ManifestLayerMapping> layerMappings, final String projectName, final String versionName, final String tarFileName, final ImageInfo imageInfo, final String architecture)
            throws FileNotFoundException, IOException, HubIntegrationException, InterruptedException {
        final File workingDirectory = new File(programPaths.getHubDockerWorkingDirPath());
        final List<File> bdioFiles = new ArrayList<>();

        ManifestLayerMapping manifestMapping = null;
        for (final ManifestLayerMapping mapping : layerMappings) {
            if (StringUtils.compare(mapping.getTargetImageFileSystemRootDirName(), imageInfo.getFileSystemRootDirName()) == 0) {
                manifestMapping = mapping;
            }
        }
        if (manifestMapping == null) {
            throw new HubIntegrationException(String.format("Mapping for %s not found in target image manifest file", imageInfo.getFileSystemRootDirName()));
        }

        String codeLocationName, hubProjectName, hubVersionName = "";
        final String imageDirectoryName = manifestMapping.getTargetImageFileSystemRootDirName();
        String pkgMgrFilePath = imageInfo.getPkgMgr().getExtractedPackageManagerDirectory().getAbsolutePath();
        pkgMgrFilePath = pkgMgrFilePath.substring(pkgMgrFilePath.indexOf(imageDirectoryName) + imageDirectoryName.length() + 1);

        codeLocationName = programPaths.getCodeLocationName(manifestMapping.getImageName(), manifestMapping.getTagName(), pkgMgrFilePath, imageInfo.getPkgMgr().getPackageManager().toString());
        hubProjectName = deriveHubProject(manifestMapping.getImageName(), projectName);
        hubVersionName = deriveHubProjectVersion(manifestMapping, versionName);
        logger.info("Hub project, version: ${hubProjectName}, ${hubVersionName}; Code location : ${codeLocationName}");
        final String bdioFilename = programPaths.getBdioFilename(manifestMapping.getImageName(), pkgMgrFilePath, hubProjectName, hubVersionName);
        logger.debug("bdioFilename: ${bdioFilename}");
        final File outputFile = new File(workingDirectory, bdioFilename);
        bdioFiles.add(outputFile);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            try (BdioWriter writer = new BdioWriter(new Gson(), outputStream);) {
                final Extractor extractor = getExtractorByPackageManager(imageInfo.getPkgMgr().getPackageManager());
                final ExtractionDetails extractionDetails = new ExtractionDetails(imageInfo.getOperatingSystemEnum(), architecture);
                extractor.extract(imageInfo.getPkgMgr(), writer, extractionDetails, codeLocationName, hubProjectName, hubVersionName);
            }
        }

        return bdioFiles;
    }

    private String deriveHubProject(final String imageName, final String projectName) {
        String hubProjectName;
        if (StringUtils.isBlank(projectName)) {
            hubProjectName = programPaths.cleanImageName(imageName);
        } else {
            logger.debug("Using project from config property");
            hubProjectName = projectName;
        }
        return hubProjectName;
    }

    private String deriveHubProjectVersion(final ManifestLayerMapping mapping, final String versionName) {
        String hubVersionName;
        if (StringUtils.isBlank(versionName)) {
            hubVersionName = mapping.getTagName();
        } else {
            logger.debug("Using project version from config property");
            hubVersionName = versionName;
        }
        return hubVersionName;
    }

    private Extractor getExtractorByPackageManager(final PackageManagerEnum packageManagerEnum) throws HubIntegrationException {
        for (final Extractor currentExtractor : extractors) {
            if (currentExtractor.getPackageManagerEnum() == packageManagerEnum) {
                return currentExtractor;
            }
        }
        throw new HubIntegrationException(String.format("Extractor not found for packageManager %s", packageManagerEnum.toString()));
    }
}
