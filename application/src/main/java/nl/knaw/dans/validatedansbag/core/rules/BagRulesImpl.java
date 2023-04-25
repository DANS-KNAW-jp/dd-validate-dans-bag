/*
 * Copyright (C) 2022 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.validatedansbag.core.rules;

import gov.loc.repository.bagit.exceptions.CorruptChecksumException;
import gov.loc.repository.bagit.exceptions.FileNotInManifestException;
import gov.loc.repository.bagit.exceptions.FileNotInPayloadDirectoryException;
import gov.loc.repository.bagit.exceptions.InvalidBagitFileFormatException;
import gov.loc.repository.bagit.exceptions.MissingBagitFileException;
import gov.loc.repository.bagit.exceptions.MissingPayloadDirectoryException;
import gov.loc.repository.bagit.exceptions.MissingPayloadManifestException;
import gov.loc.repository.bagit.exceptions.VerificationException;
import gov.loc.repository.bagit.hash.StandardSupportedAlgorithms;
import nl.knaw.dans.validatedansbag.core.BagNotFoundException;
import nl.knaw.dans.validatedansbag.core.engine.RuleResult;
import nl.knaw.dans.validatedansbag.core.service.BagItMetadataReader;
import nl.knaw.dans.validatedansbag.core.service.FileService;
import nl.knaw.dans.validatedansbag.core.service.FilesXmlService;
import nl.knaw.dans.validatedansbag.core.service.OriginalFilepathsService;
import nl.knaw.dans.validatedansbag.core.service.XmlReader;
import nl.knaw.dans.validatedansbag.core.validator.IdentifierValidator;
import nl.knaw.dans.validatedansbag.core.validator.LicenseValidator;
import nl.knaw.dans.validatedansbag.core.validator.OrganizationIdentifierPrefixValidator;
import nl.knaw.dans.validatedansbag.core.validator.PolygonListValidator;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathExpressionException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BagRulesImpl implements BagRules {
    private static final Logger log = LoggerFactory.getLogger(BagRulesImpl.class);

    private final FileService fileService;

    private final BagItMetadataReader bagItMetadataReader;
    private final XmlReader xmlReader;

    private final OriginalFilepathsService originalFilepathsService;

    private final Pattern doiPattern = Pattern.compile("^10(\\.\\d+)+/.+");

    private final IdentifierValidator identifierValidator;

    private final PolygonListValidator polygonListValidator;

    private final LicenseValidator licenseValidator;

    private final OrganizationIdentifierPrefixValidator organizationIdentifierPrefixValidator;

    private final FilesXmlService filesXmlService;

    public BagRulesImpl(FileService fileService, BagItMetadataReader bagItMetadataReader, XmlReader xmlReader, OriginalFilepathsService originalFilepathsService,
        IdentifierValidator identifierValidator,
        PolygonListValidator polygonListValidator, LicenseValidator licenseValidator, OrganizationIdentifierPrefixValidator organizationIdentifierPrefixValidator, FilesXmlService filesXmlService) {
        this.fileService = fileService;
        this.bagItMetadataReader = bagItMetadataReader;
        this.xmlReader = xmlReader;
        this.originalFilepathsService = originalFilepathsService;
        this.identifierValidator = identifierValidator;
        this.polygonListValidator = polygonListValidator;
        this.licenseValidator = licenseValidator;
        this.organizationIdentifierPrefixValidator = organizationIdentifierPrefixValidator;
        this.filesXmlService = filesXmlService;
    }

    @Override
    public BagValidatorRule bagIsValid() {
        return (path) -> {
            try {
                log.debug("Verifying bag {}", path);
                bagItMetadataReader.verifyBag(path);
                log.debug("Bag {} is valid", path);
                return RuleResult.ok();
            }
            // only catch exceptions that have to do with the bag verification;
            // other exceptions such as IOException should be propagated to the rule engine
            // sadly FileNotInManifestException bubbles up as an IOException
            catch (FileNotInManifestException | InvalidBagitFileFormatException | MissingPayloadManifestException | MissingPayloadDirectoryException | FileNotInPayloadDirectoryException | MissingBagitFileException |
                   CorruptChecksumException | VerificationException | NoSuchFileException e) {

                return RuleResult.error(String.format(
                    "Bag is not valid: %s", e.getMessage()
                ), e);
            }
        };
    }

    @Override
    public BagValidatorRule containsDir(Path dir) {
        return ((path) -> {
            var target = path.resolve(dir);

            if (!fileService.isDirectory(target)) {
                return RuleResult.error(String.format("Path '%s' is not a directory", dir));
            }

            return RuleResult.ok();
        });
    }

    @Override
    public BagValidatorRule containsFile(Path file) {
        return ((path) -> {
            var target = path.resolve(file);

            if (!fileService.isFile(target)) {
                return RuleResult.error(String.format("Path '%s' is not a file", file));
            }

            return RuleResult.ok();
        });
    }

    @Override
    public BagValidatorRule bagInfoExistsAndIsWellFormed() {
        return path -> {
            if (!fileService.isFile(path.resolve(Path.of("bag-info.txt")))) {
                return RuleResult.error("bag-info.txt does not exist");
            }

            try {
                log.debug("Reading bag metadata from {}", path);
                bagItMetadataReader.getBag(path).orElseThrow();
                return RuleResult.ok();
            }
            catch (Exception e) {
                return RuleResult.error(String.format(
                    "bag-info.txt exists but is malformed: %s", e.getMessage()
                ), e);
            }
        };
    }

    @Override
    public BagValidatorRule bagInfoCreatedElementIsIso8601Date() {
        return path -> {
            var created = bagItMetadataReader.getSingleField(path, "Created");

            try {
                log.debug("Trying to parse date {} to see if it is valid", created);
                DateTime.parse(created, ISODateTimeFormat.dateTime());
                return RuleResult.ok();
            }
            catch (Throwable e) {
                return RuleResult.error(String.format(
                    "Date '%s' is not valid", created
                ), e);
            }
        };
    }

    @Override
    public BagValidatorRule bagInfoContainsExactlyOneOf(String key) {
        return path -> {
            var items = bagItMetadataReader.getField(path, key);
            log.debug("Found {} results in bag {} for field {}", items, path, key);

            if (items.size() != 1) {
                return RuleResult.error(
                    String.format("bag-info.txt must contain exactly one '%s' element; number found: %s", key, items.size())
                );
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule bagInfoContainsAtMostOneOf(String key) {
        return path -> {
            var items = bagItMetadataReader.getField(path, key);
            log.debug("Found {} results in bag {} for field {}", items, path, key);

            switch (items.size()) {
                case 0:
                    return RuleResult.skipDependencies();
                case 1:
                    return RuleResult.ok();
                default:
                    return RuleResult.error(
                        String.format("bag-info.txt may contain at most one element: '%s'", key)
                    );

            }
        };
    }

    @Override
    public BagValidatorRule bagInfoIsVersionOfIsValidUrnUuid() {
        return path -> {
            var items = bagItMetadataReader.getField(path, "Is-Version-Of");

            var invalidUrns = items.stream().filter(item -> {
                    log.trace("Validating if {} is a valid URN UUID ", item);

                    try {
                        var uri = new URI(item);

                        if (!"urn".equalsIgnoreCase(uri.getScheme())) {
                            log.trace("{} is not the expected value 'urn'", uri.getScheme());
                            return true;
                        }

                        if (!uri.getSchemeSpecificPart().startsWith("uuid:")) {
                            log.trace("{} does not start with 'uuid:'", uri.getSchemeSpecificPart());
                            return true;
                        }

                        //noinspection ResultOfMethodCallIgnored
                        UUID.fromString(uri.getSchemeSpecificPart().substring("uuid:".length()));
                    }
                    catch (URISyntaxException | IllegalArgumentException e) {
                        log.trace("{} could not be parsed", item, e);
                        return true;
                    }

                    return false;
                })
                .collect(Collectors.toList());

            log.debug("Invalid URN UUID's from this list ({}) are {}", items, invalidUrns);

            if (!invalidUrns.isEmpty()) {
                return RuleResult.error(
                    String.format("bag-info.txt Is-Version-Of value must be a valid URN: Invalid items {%s}", String.join(", ", invalidUrns))
                );
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule containsNothingElseThan(Path dir, String[] paths) {
        return (path) -> {
            var basePath = path.resolve(dir);
            var allowed = Arrays.stream(paths)
                .map(Path::of)
                .collect(Collectors.toSet());

            var allItems = fileService.getAllFilesAndDirectories(basePath)
                .stream()
                .filter(p -> !basePath.equals(p))
                .map(basePath::relativize)
                .filter(p -> !allowed.contains(p))
                // filter out the parent path
                .collect(Collectors.toSet());

            log.debug("Found items that are not allowed in path {}: {} (allowed files are {})",
                basePath, allItems, allowed);

            if (allItems.size() > 0) {
                var filenames = allItems.stream().map(Path::toString).collect(Collectors.joining(", "));

                return RuleResult.error(String.format(
                    "Directory %s contains files or directories that are not allowed: %s",
                    dir, filenames
                ));
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule mustNotContain(Path dir, String[] paths) {
        return (path) -> {
            var basePath = path.resolve(dir);
            var notAllowed = Arrays.stream(paths)
                .map(Path::of)
                .collect(Collectors.toSet());

            var foundButNotAllowedItems = fileService.getAllFilesAndDirectories(basePath)
                .stream()
                .filter(p -> !basePath.equals(p))
                .map(basePath::relativize)
                .filter(notAllowed::contains)
                // filter out the parent path
                .collect(Collectors.toSet());

            log.debug("Found items that are not allowed in path {}: {} ", basePath, foundButNotAllowedItems);

            if (foundButNotAllowedItems.size() > 0) {
                var filenames = foundButNotAllowedItems.stream().map(Path::toString).collect(Collectors.joining(", "));

                return RuleResult.error(String.format(
                    "Directory %s contains files or directories that are not allowed: %s",
                    dir, filenames
                ));
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule optionalFileIsUtf8Decodable(Path filename) {
        return (path) -> {
            try {
                var target = path.resolve(filename);

                if (fileService.exists(target)) {
                    fileService.readFileContents(target, StandardCharsets.UTF_8);
                    return RuleResult.ok();
                }
                else {
                    return RuleResult.skipDependencies();
                }
            }
            catch (CharacterCodingException e) {
                return RuleResult.error("Input not valid UTF-8: " + e.getMessage());
            }
        };
    }

    @Override
    public BagValidatorRule isOriginalFilepathsFileComplete() {
        return (path) -> {
            if (!originalFilepathsService.exists(path)) {
                return RuleResult.skipDependencies();
            }

            var mapping = originalFilepathsService.getMapping(path);

            // the files defined in metadata/files.xml
            var fileXmlPaths = filesXmlService.readFilepaths(path)
                .collect(Collectors.toSet());

            log.trace("Paths in files.xml: {}", fileXmlPaths);

            // the files on disk
            var dataPath = path.resolve("data");
            var actualFiles = fileService.getAllFiles(dataPath)
                .stream()
                .filter(i -> !dataPath.equals(i))
                .map(path::relativize)
                .collect(Collectors.toSet());

            log.trace("Paths inside {}: {}", dataPath, fileXmlPaths);

            var renamedFiles = mapping.stream().map(OriginalFilepathsService.OriginalFilePathItem::getRenamedFilename).collect(Collectors.toSet());
            var originalFiles = mapping.stream().map(OriginalFilepathsService.OriginalFilePathItem::getOriginalFilename).collect(Collectors.toSet());

            // disjunction returns the difference between 2 sets
            // so {1,2,3} disjunction {2,3,4} would return {1,4}
            var physicalFileSetsDiffer = CollectionUtils.disjunction(actualFiles, renamedFiles).size() > 0;
            log.trace("Disjunction between files on disk and files referenced in original-filepaths.txt: {}", physicalFileSetsDiffer);

            var originalFileSetsDiffer = CollectionUtils.disjunction(fileXmlPaths, originalFiles).size() > 0;
            log.trace("Disjunction between files.xml and files referenced in original-filepaths.txt: {}", originalFiles);

            if (physicalFileSetsDiffer || originalFileSetsDiffer) {
                log.debug("File sets are not equal, physicalFileSetsDiffer = {} and originalFileSetsDiffer = {}", physicalFileSetsDiffer, originalFileSetsDiffer);

                //  items that exist only in actual files, but not in the keyset of mapping and not in the files.xml
                var onlyInBag = CollectionUtils.subtract(actualFiles, renamedFiles);

                // files that only exist in files.xml, but not in the original-filepaths.txt
                var onlyInFilesXml = CollectionUtils.subtract(fileXmlPaths, originalFiles);

                // files that only exist in original-filepaths.txt, but not on the disk
                var onlyInFilepathsPhysical = CollectionUtils.subtract(renamedFiles, actualFiles);

                // files that only exist in original-filepaths.txt, but not in files.xml
                var onlyInFilepathsOriginal = CollectionUtils.subtract(originalFiles, fileXmlPaths);

                var message = new StringBuilder();

                if (physicalFileSetsDiffer) {
                    message.append("  - Physical file paths in original-filepaths.txt not equal to payload in data dir. Difference - ");
                    message.append("only in payload: {")
                        .append(onlyInBag.stream().map(Path::toString).collect(Collectors.joining(", ")))
                        .append("}");
                    message.append(", only in physical-bag-relative-path: {")
                        .append(onlyInFilepathsPhysical.stream().map(Path::toString).collect(Collectors.joining(", ")))
                        .append("}");
                    message.append("\n");
                }

                if (originalFileSetsDiffer) {
                    message.append("  - Original file paths in original-filepaths.txt not equal to filepaths in files.xml. Difference - ");
                    message.append("only in files.xml: {")
                        .append(onlyInFilesXml.stream().map(Path::toString).collect(Collectors.joining(", ")))
                        .append("}");
                    message.append(", only in original-bag-relative-path: {")
                        .append(onlyInFilepathsOriginal.stream().map(Path::toString).collect(Collectors.joining(", ")))
                        .append("}");
                    message.append("\n");
                }

                return RuleResult.error(String.format(
                    "original-filepaths.txt errors: \n%s", message
                ));
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmMustContainDctermsLicense() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            // converts a namespace uri into a prefix that is used in the document
            var prefix = document.lookupPrefix(XmlReader.NAMESPACE_DCTERMS);
            var expr = String.format("/ddm:DDM/ddm:dcmiMetadata/dcterms:license[@xsi:type='%s:URI']", prefix);

            var validNodes = xmlReader.xpathToStream(document, expr)
                .filter(item -> licenseValidator.isValidLicenseURI(item.getTextContent()))
                .collect(Collectors.toList());

            log.debug("Found {} nodes with correct licenses", validNodes.size());

            var numLicensesFound = validNodes.size();

            if (numLicensesFound == 1) {
                return RuleResult.ok();
            }
            else if (numLicensesFound == 0) {
                return RuleResult.error("No license with xsi:type=\"dcterms:URI\"");
            }
            else {
                return RuleResult.error("More than one license with xsi:type=\"dcterms:URI\"");
            }
        };
    }

    @Override
    public BagValidatorRule ddmDoiIdentifiersAreValid() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "/ddm:DDM/ddm:dcmiMetadata/dcterms:identifier[@xsi:type=\"id-type:DOI\"]";

            var nodes = xmlReader.xpathToStreamOfStrings(document, expr);
            var match = nodes
                .peek(node -> {
                    log.trace("Validating if {} matches pattern {}", node, doiPattern);
                })
                .filter((text) -> {
                    return !doiPattern.matcher(text).matches();
                })
                .collect(Collectors.joining(", "));

            log.debug("Identifiers (DOI) that do not match the pattern: {}", match);

            if (match.length() > 0) {
                return RuleResult.error(String.format(
                    "dataset.xml: Invalid DOIs: %s", match
                ));
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmDaisAreValid() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "//dcx-dai:DAI";
            var match = xmlReader.xpathToStreamOfStrings(document, expr)
                .peek(id -> {
                    log.trace("Validating if {} is a valid DAI", id);
                })
                .filter((id) -> !identifierValidator.validateDai(id))
                .collect(Collectors.toList());

            log.debug("Identifiers (DAI) that do not match the pattern: {}", match);

            if (!match.isEmpty()) {
                var message = String.join(", ", match);
                return RuleResult.error("dataset.xml: Invalid DAIs: " + message);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmIsnisAreValid() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "//dcx-dai:ISNI";
            var match = xmlReader.xpathToStreamOfStrings(document, expr)
                .peek(id -> {
                    log.trace("Validating if {} is a valid ISNI", id);
                })
                .filter((id) -> !identifierValidator.validateIsni(id))
                .collect(Collectors.toList());

            log.debug("Identifiers (ISNI) that do not match the pattern: {}", match);

            if (!match.isEmpty()) {
                var message = String.join(", ", match);
                return RuleResult.error("dataset.xml: Invalid ISNI(s): " + message);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmOrcidsAreValid() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "//dcx-dai:ORCID";
            var match = xmlReader.xpathToStreamOfStrings(document, expr)
                .peek(id -> {
                    log.trace("Validating if {} is a valid ISNI", id);
                })
                .filter((id) -> !identifierValidator.validateOrcid(id))
                .collect(Collectors.toList());

            log.debug("Identifiers (ORCID) that do not match the pattern: {}", match);

            if (!match.isEmpty()) {
                var message = String.join(", ", match);
                return RuleResult.error("dataset.xml: Invalid ORCID(s): " + message);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmGmlPolygonPosListIsWellFormed() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "//dcx-gml:spatial//gml:posList";//*[local-name() = 'posList']";
            var nodes = xmlReader.xpathToStreamOfStrings(document, expr);

            var match = nodes
                .peek(posList -> {
                    log.trace("Validation posList value {}", posList);
                })
                .map(polygonListValidator::validatePolygonList)
                .filter(e -> !e.isValid())
                .map(PolygonListValidator.PolygonValidationResult::getMessage)
                .collect(Collectors.toList());

            log.debug("Invalid posList elements: {}", match);

            if (!match.isEmpty()) {
                var message = String.join("\n", match);
                return RuleResult.error("dataset.xml: Invalid posList: " + message);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule polygonsInSameMultiSurfaceHaveSameSrsName() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));
            var expr = "//gml:MultiSurface";
            var nodes = xmlReader.xpathToStream(document, expr);
            var match = nodes.filter(node -> {
                    try {
                        var srsNames = xmlReader.xpathToStreamOfStrings(node, ".//gml:Polygon/@srsName")
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                        log.trace("Found unique srsName values: {}", srsNames);
                        if (srsNames.size() > 1) {
                            return true;
                        }
                    }
                    catch (Throwable e) {
                        log.error("Error checking srsNames attribute", e);
                        return true;
                    }

                    return false;
                })
                .collect(Collectors.toList());

            log.debug("Invalid MultiSurface elements that contain polygons with different srsNames: {}", match);

            if (!match.isEmpty()) {
                return RuleResult.error("dataset.xml: Found MultiSurface element containing polygons with different srsNames");
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule pointsHaveAtLeastTwoValues() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            // points
            var expr = "//gml:Point/gml:pos | //gml:lowerCorner | //gml:upperCorner";

            var errors = xmlReader.xpathToStream(document, expr)
                .map(value -> {
                    var attr = value.getParentNode().getAttributes().getNamedItem("srsName");
                    var text = value.getTextContent();
                    var isRD = attr != null && "urn:ogc:def:crs:EPSG::28992".equals(attr.getTextContent());

                    log.trace("Validating point {} (isRD: {})", text, isRD);

                    try {
                        var parts = Arrays.stream(text.split("\\s+"))
                            .map(String::trim)
                            .map(Float::parseFloat)
                            .collect(Collectors.toList());

                        if (parts.size() < 2) {
                            return String.format(
                                "%s has less than two coordinates: %s", value.getLocalName(), text
                            );
                        }

                        else if (isRD) {
                            var x = parts.get(0);
                            var y = parts.get(1);

                            var valid = x >= -7000 && x <= 300000 && y >= 289000 && y <= 629000;

                            if (!valid) {
                                return String.format(
                                    "%s is outside RD bounds: %s", value.getLocalName(), text
                                );
                            }
                        }
                    }
                    catch (NumberFormatException e) {
                        return String.format(
                            "%s has non numeric coordinates: %s", value.getLocalName(), text
                        );
                    }

                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            log.debug("Errors while validating points: {}", errors);

            if (errors.size() > 0) {
                return RuleResult.error(errors);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule archisIdentifiersHaveAtMost10Characters() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            // points
            var expr = "/ddm:DDM/ddm:dcmiMetadata/dcterms:identifier[@xsi:type = 'id-type:ARCHIS-ZAAK-IDENTIFICATIE']";
            var match = xmlReader.xpathToStreamOfStrings(document, expr)
                .filter(Objects::nonNull)
                .peek(text -> {
                    log.trace("Validating element text '{}' for maximum length", text);
                })
                .filter(text -> text.length() > 10)
                .collect(Collectors.toList());

            log.debug("Invalid Archis identifiers: {}", match);

            if (match.size() > 0) {
                var errors = match.stream().map(e -> String.format(
                    "dataset.xml: Archis identifier must be 10 or fewer characters long: %s", e
                )).collect(Collectors.toList());

                return RuleResult.error(errors);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule allUrlsAreValid() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            var hrefNodes = xmlReader.xpathToStreamOfStrings(document, "//*/@href");
            var schemeURINodes = xmlReader.xpathToStreamOfStrings(document, "//ddm:subject/@schemeURI");
            var valueURINodes = xmlReader.xpathToStreamOfStrings(document, "//ddm:subject/@valueURI");

            var expr = List.of(
                "//*[@xsi:type='dcterms:URI']",
                "//*[@xsi:type='dcterms:URL']",
                "//*[@xsi:type='URI']",
                "//*[@xsi:type='URL']",
                "//*[@scheme='dcterms:URI']",
                "//*[@scheme='dcterms:URL']",
                "//*[@scheme='URI']",
                "//*[@scheme='URL']"
            );

            var elementSelectors = xmlReader.xpathsToStreamOfStrings(document, expr);

            var errors = Stream.of(hrefNodes, schemeURINodes, valueURINodes, elementSelectors)
                .flatMap(i -> i)
                .map(value -> {
                    log.trace("Validating URI '{}'", value);

                    try {
                        var uri = new URI(value);

                        if (uri.getScheme() == null || !List.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                            return String.format(
                                "dataset.xml: protocol '%s' in uri '%s' is not one of the accepted protocols [http, https]", uri.getScheme(), uri
                            );
                        }
                    }
                    catch (URISyntaxException e) {
                        return String.format("dataset.xml: '%s' is not a valid uri", value);
                    }

                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

            log.debug("Invalid URI's found: {}", errors);

            if (errors.size() > 0) {
                return RuleResult.error(errors);
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmMustHaveRightsHolderDeposit() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            // in case of deposit, it may also be in the dcterms:rightsHolder element
            var rightsHolder = getRightsHolderInElement(document);

            if (rightsHolder.isEmpty()) {
                return RuleResult.error("No RightsHolder found in <dcterms:rightsHolder> element");
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmMustHaveRightsHolderMigration() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            var inRole = getRightsHolderInAuthor(document);
            var rightsHolder = getRightsHolderInElement(document);
            log.debug("Results for rights holder search, inRole {}, in rightsHolder element {}", inRole, rightsHolder);

            if (inRole.isEmpty() && rightsHolder.isEmpty()) {
                return RuleResult.error("No RightsHolder found in <dcx-dai:role> nor in <rightsHolder> element");
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule ddmMustNotHaveRightsHolderRole() {
        return (path) -> {
            var document = xmlReader.readXmlFile(path.resolve("metadata/dataset.xml"));

            var inRole = getRightsHolderInAuthor(document);
            log.debug("Results for rights holder search, inRole {}", inRole);

            if (inRole.isPresent()) {
                return RuleResult.error("RightsHolder found in <dcx-dai:role>");
            }

            return RuleResult.ok();
        };
    }

    private Optional<String> getRightsHolderInElement(Document document) throws XPathExpressionException {
        return xmlReader.xpathToStreamOfStrings(document, "/ddm:DDM/ddm:dcmiMetadata//dcterms:rightsHolder")
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .findFirst();
    }

    private Optional<String> getRightsHolderInAuthor(Document document) throws XPathExpressionException {
        return xmlReader.xpathToStreamOfStrings(document, "//dcx-dai:author/dcx-dai:role")
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> value.equals("RightsHolder"))
            .findFirst();
    }

    @Override
    public BagValidatorRule containsNotJustMD5Manifest() {
        return path -> {
            var bag = bagItMetadataReader.getBag(path).orElseThrow(
                () -> new BagNotFoundException(String.format("Bag on path %s could not be opened", path)));

            var manifests = bagItMetadataReader.getBagManifests(bag);
            var hasOtherManifests = false;

            log.debug("Manifests to compare: {}", manifests);

            for (var manifest : manifests) {
                log.trace("Checking if manifest {} has MD5 algorithm (algorithm is {})", manifest, manifest.getAlgorithm());

                if (!StandardSupportedAlgorithms.MD5.equals(manifest.getAlgorithm())) {
                    hasOtherManifests = true;
                    break;
                }
            }

            if (!hasOtherManifests) {
                return RuleResult.error("The bag contains no manifests or only a MD5 manifest");
            }

            return RuleResult.ok();
        };
    }

    @Override
    public BagValidatorRule organizationalIdentifierPrefixIsValid() {
        return path -> {
            var hasOrganizationalIdentifier = bagItMetadataReader.getSingleField(path, "Has-Organizational-Identifier");

            log.debug("Checking prefix on organizational identifier '{}'", hasOrganizationalIdentifier);

            var isValid = organizationIdentifierPrefixValidator.hasValidPrefix(hasOrganizationalIdentifier);

            if (!isValid) {
                return RuleResult.error(String.format("No valid prefix given for value of 'Has-Organizational-Identifier': %s", hasOrganizationalIdentifier));
            }

            return RuleResult.ok();
        };
    }
}
