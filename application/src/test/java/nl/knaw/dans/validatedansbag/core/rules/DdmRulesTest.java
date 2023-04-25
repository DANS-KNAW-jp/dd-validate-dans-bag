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

import nl.knaw.dans.validatedansbag.core.engine.RuleResult;
import nl.knaw.dans.validatedansbag.core.service.XmlReader;
import nl.knaw.dans.validatedansbag.core.service.XmlReaderImpl;
import nl.knaw.dans.validatedansbag.core.service.XmlSchemaValidator;
import nl.knaw.dans.validatedansbag.core.service.XmlSchemaValidatorImpl;
import nl.knaw.dans.validatedansbag.core.validator.PolygonListValidatorImpl;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DdmRulesTest {

    private static final BagValidatorRule conformsToDdmSchemaRule = getConformsToDdmSchemaRule(); // 3.1.1
    private static final BagRules bagRules = getBagRules(); // 3.1.2 - 3.1.11
    private final Path testDir = Paths.get("target/test/").resolve(this.getClass().getSimpleName());

    @BeforeEach
    public void clear() throws IOException {
        FileUtils.deleteDirectory(testDir.toFile());
    }

    private void writeDdm(String... ddmLines) throws Exception {
        File ddmFile = testDir.resolve("metadata").resolve("dataset.xml").toFile();
        FileUtils.writeLines(ddmFile, List.of(ddmLines));
    }

    @Test
    public void points_should_be_within_boundaries() throws Exception {
        writeDdm("<ddm:DDM",
            "        xmlns:ddm='http://schemas.dans.knaw.nl/dataset/ddm-v2/'",
            "        xmlns:dct='http://purl.org/dc/terms/'",
            "        xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'",
            "        xmlns:dcx-gml='http://easy.dans.knaw.nl/schemas/dcx/gml/'",
            ">",
            "    <ddm:dcmiMetadata>",
            "            <dcx-gml:spatial>",
            "                <boundedBy xmlns='http://www.opengis.net/gml'>",
            "                    <Envelope srsName='urn:ogc:def:crs:EPSG::28992'>",
            "                        <lowerCorner>-7000 289000</lowerCorner>",
            "                        <upperCorner>300000 629000</upperCorner>",
            "                    </Envelope>",
            "                </boundedBy>",
            "            </dcx-gml:spatial>",
            "    </ddm:dcmiMetadata>",
            "</ddm:DDM>");

        // 3.1.7
        assertThat(bagRules.pointsHaveAtLeastTwoValues().validate(testDir))
            .hasFieldOrPropertyWithValue("status", RuleResult.Status.SUCCESS);
    }

    @Test
    public void points_should_report_problems() throws Exception {
        writeDdm("<ddm:DDM",
            "        xmlns:ddm='http://schemas.dans.knaw.nl/dataset/ddm-v2/'",
            "        xmlns:dct='http://purl.org/dc/terms/'",
            "        xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'",
            "        xmlns:dcx-gml='http://easy.dans.knaw.nl/schemas/dcx/gml/'",
            ">",
            "    <ddm:dcmiMetadata>",
            "            <dct:spatial xsi:type='dcx-gml:SimpleGMLType'>",
            "                <Point xmlns='http://www.opengis.net/gml'>",
            "                    <pos>1.0</pos><!-- only one value -->",
            "                </Point>",
            "            </dct:spatial>",
            "            <dct:spatial xsi:type='dcx-gml:SimpleGMLType'>",
            "                <Point xmlns='http://www.opengis.net/gml'>",
            "                    <pos>a 5</pos><!-- non numeric -->",
            "                </Point>",
            "            </dct:spatial>",
            "            <dct:spatial xsi:type='dcx-gml:SimpleGMLType'>",
            "                <Point xmlns='http://www.opengis.net/gml'>",
            "                    <pos>1 2 3</pos>",
            "                </Point>",
            "            </dct:spatial>",
            "            <dcx-gml:spatial>",
            "                <boundedBy xmlns='http://www.opengis.net/gml'>",
            "                    <Envelope srsName='urn:ogc:def:crs:EPSG::28992'>",
            "                        <lowerCorner>-7001 289000</lowerCorner>",
            "                        <upperCorner>300001 629000</upperCorner>",
            "                    </Envelope>",
            "                </boundedBy>",
            "            </dcx-gml:spatial>",
            "            <dcx-gml:spatial>",
            "                <boundedBy xmlns='http://www.opengis.net/gml'>",
            "                    <Envelope srsName='urn:ogc:def:crs:EPSG::28992'>",
            "                        <lowerCorner>-7000 288999</lowerCorner>",
            "                        <upperCorner>300000 629001</upperCorner>",
            "                    </Envelope>",
            "                </boundedBy>",
            "            </dcx-gml:spatial>",
            "            <dcx-gml:spatial>",
            "                <boundedBy xmlns='http://www.opengis.net/gml'>",
            "                    <Envelope><!-- no srsName -->",
            "                        <lowerCorner>-7000</lowerCorner>",
            "                    </Envelope>",
            "                </boundedBy>",
            "            </dcx-gml:spatial>",
            "    </ddm:dcmiMetadata>",
            "</ddm:DDM>");

        // 3.1.1 can parse but is not schemaValid, other rules can execute
        assertThat(conformsToDdmSchemaRule.validate(testDir).getErrorMessages().get(0))
            .contains("Attribute 'srsName' must appear on element 'Envelope'")
            .contains("upperCorner}' is expected");

        // 3.1.7
        assertThat(bagRules.pointsHaveAtLeastTwoValues().validate(testDir).getErrorMessages())
            .hasSameElementsAs(List.of(
                "pos has less than two coordinates: 1.0",
                "pos has non numeric coordinates: a 5",
                "lowerCorner is outside RD bounds: -7001 289000", // x too small
                "upperCorner is outside RD bounds: 300001 629000", // x too large
                "lowerCorner is outside RD bounds: -7000 288999", // y too small
                "upperCorner is outside RD bounds: 300000 629001", // y too large
                "lowerCorner has less than two coordinates: -7000"));
        // TODO messages should be more specific, for example replace Point with pos/lowerCorner/upperCorner
        // TODO validate that upper/lower not mixed up?
    }

    @Test
    public void depending_rule_throws_if_parse_fails() throws Exception {
        writeDdm("<ddm:DDM",
            "        xmlns:ddm='http://schemas.dans.knaw.nl/dataset/ddm-v2/'",
            "        xmlns:dct='http://purl.org/dc/terms/'",
            "        xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'",
            "        xmlns:dcx-gml='http://easy.dans.knaw.nl/schemas/dcx/gml/'",
            ">",
            "    <ddm:dcmiMetadata>",
            "            <dct:spatial xsi:type='dcx-gml:SimpleGMLType'>",
            "                <Point xmlns='http://www.opengis.net/gml'>",
            "                    <pos>1 0</pos>",
            "                </Point>",
            "    </ddm:dcmiMetadata>",
            "</ddm:DDM>");

        String expected = "must be terminated by the matching end-tag";

        // 3.1.1
        assertThat(conformsToDdmSchemaRule.validate(testDir).getErrorMessages().get(0))
            .contains(expected);

        // 3.1.7 TODO it would be better to have a dependency on "canParse" than on schemaValid
        assertThatThrownBy(() -> bagRules.pointsHaveAtLeastTwoValues().validate(testDir))
            .hasMessageContaining(expected);
    }

    private static BagRules getBagRules() {
        return new BagRulesImpl(null, null, new XmlReaderImpl(), null, null, new PolygonListValidatorImpl(), null,
            null, null);
    }

    private static BagValidatorRule getConformsToDdmSchemaRule() {
        final URI schema;
        try {
            // TODO maven plugin for a local copy under target
            schema = new URI("https://raw.githubusercontent.com/DANS-KNAW-jp/dans-schema/master/lib/src/main/resources/md/ddm/v2/ddm.xsd");
        }
        catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        final XmlSchemaValidator xmlSchemaValidator = new XmlSchemaValidatorImpl(Map.of("dataset.xml", schema));
        final XmlReader xmlReader = new XmlReaderImpl();
        var xmlRules = new XmlRulesImpl(xmlReader, xmlSchemaValidator, null);
        Path ddmFile = Paths.get("metadata/dataset.xml");
        return xmlRules.xmlFileConformsToSchema(ddmFile, "dataset.xml");
    }
}
