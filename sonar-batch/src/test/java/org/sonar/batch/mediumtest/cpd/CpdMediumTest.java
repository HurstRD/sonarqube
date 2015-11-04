/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.mediumtest.cpd;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.assertj.core.groups.Tuple;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.batch.mediumtest.BatchMediumTester;
import org.sonar.batch.mediumtest.TaskResult;
import org.sonar.batch.protocol.output.BatchReport.DuplicationBlock;
import org.sonar.batch.protocol.output.BatchReport.Measure;
import org.sonar.xoo.XooPlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class CpdMediumTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  public BatchMediumTester tester = BatchMediumTester.builder()
    .registerPlugin("xoo", new XooPlugin())
    .addDefaultQProfile("xoo", "Sonar Way")
    .build();

  private File baseDir;

  private ImmutableMap.Builder<String, String> builder;

  @Before
  public void prepare() throws IOException {
    tester.start();

    baseDir = temp.getRoot();

    builder = ImmutableMap.<String, String>builder()
      .put("sonar.task", "scan")
      .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
      .put("sonar.projectKey", "com.foo.project")
      .put("sonar.projectName", "Foo Project")
      .put("sonar.projectVersion", "1.0-SNAPSHOT")
      .put("sonar.projectDescription", "Description of Foo Project");
  }

  @After
  public void stop() {
    tester.stop();
  }

  @Test
  public void testCrossFileDuplications() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    String duplicatedStuff = "Sample xoo\ncontent\nfoo\nbar\ntoto\ntiti\nfoo\nbar\ntoto\ntiti\nbar\ntoto\ntiti\nfoo\nbar\ntoto\ntiti";

    File xooFile1 = new File(srcDir, "sample1.xoo");
    FileUtils.write(xooFile1, duplicatedStuff);

    File xooFile2 = new File(srcDir, "sample2.xoo");
    FileUtils.write(xooFile2, duplicatedStuff);

    TaskResult result = tester.newTask()
      .properties(builder
        .put("sonar.sources", "src")
        .put("sonar.cpd.xoo.minimumTokens", "10")
        .put("sonar.verbose", "true")
        .build())
      .start();

    assertThat(result.inputFiles()).hasSize(2);

    Map<String, List<org.sonar.batch.protocol.output.BatchReport.Measure>> allMeasures = result.allMeasures();
    assertThat(allMeasures.get("com.foo.project:src/sample1.xoo")).extracting("metricKey", "intValue")
      .contains(tuple("duplicated_blocks", 1),
        tuple("duplicated_files", 1),
        tuple("duplicated_lines", 17));

    InputFile inputFile1 = result.inputFile("src/sample1.xoo");
    InputFile inputFile2 = result.inputFile("src/sample2.xoo");

    // One clone group on each file
    List<org.sonar.batch.protocol.output.BatchReport.Duplication> duplicationGroupsFile1 = result.duplicationsFor(inputFile1);
    assertThat(duplicationGroupsFile1).hasSize(1);

    org.sonar.batch.protocol.output.BatchReport.Duplication cloneGroupFile1 = duplicationGroupsFile1.get(0);
    assertThat(cloneGroupFile1.getOriginPosition().getStartLine()).isEqualTo(1);
    assertThat(cloneGroupFile1.getOriginPosition().getEndLine()).isEqualTo(17);
    assertThat(cloneGroupFile1.getDuplicateList()).hasSize(1);
    assertThat(cloneGroupFile1.getDuplicate(0).getOtherFileRef()).isEqualTo(result.getReportComponent(((DefaultInputFile) inputFile2).key()).getRef());

    List<org.sonar.batch.protocol.output.BatchReport.Duplication> duplicationGroupsFile2 = result.duplicationsFor(inputFile2);
    assertThat(duplicationGroupsFile2).hasSize(1);

    org.sonar.batch.protocol.output.BatchReport.Duplication cloneGroupFile2 = duplicationGroupsFile2.get(0);
    assertThat(cloneGroupFile2.getOriginPosition().getStartLine()).isEqualTo(1);
    assertThat(cloneGroupFile2.getOriginPosition().getEndLine()).isEqualTo(17);
    assertThat(cloneGroupFile2.getDuplicateList()).hasSize(1);
    assertThat(cloneGroupFile2.getDuplicate(0).getOtherFileRef()).isEqualTo(result.getReportComponent(((DefaultInputFile) inputFile1).key()).getRef());

    assertThat(result.duplicationBlocksFor(inputFile1)).isEmpty();
  }

  @Test
  public void enableCrossProjectDuplication() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    String duplicatedStuff = "Sample xoo\ncontent\nfoo\nbar\ntoto\ntiti\nfoo";

    File xooFile1 = new File(srcDir, "sample1.xoo");
    FileUtils.write(xooFile1, duplicatedStuff);

    TaskResult result = tester.newTask()
      .properties(builder
        .put("sonar.sources", "src")
        .put("sonar.cpd.xoo.minimumTokens", "1")
        .put("sonar.cpd.xoo.minimumLines", "5")
        .put("sonar.verbose", "true")
        .put("sonar.cpd.cross_project", "true")
        .build())
      .start();

    InputFile inputFile1 = result.inputFile("src/sample1.xoo");

    List<DuplicationBlock> duplicationBlocks = result.duplicationBlocksFor(inputFile1);
    assertThat(duplicationBlocks).hasSize(3);
    assertThat(duplicationBlocks.get(0).getStartLine()).isEqualTo(1);
    assertThat(duplicationBlocks.get(0).getEndLine()).isEqualTo(5);
    assertThat(duplicationBlocks.get(0).getStartTokenIndex()).isEqualTo(1);
    assertThat(duplicationBlocks.get(0).getEndTokenIndex()).isEqualTo(6);
    assertThat(duplicationBlocks.get(0).getHashList()).isNotEmpty();

    assertThat(duplicationBlocks.get(1).getStartLine()).isEqualTo(2);
    assertThat(duplicationBlocks.get(1).getEndLine()).isEqualTo(6);
    assertThat(duplicationBlocks.get(1).getStartTokenIndex()).isEqualTo(3);
    assertThat(duplicationBlocks.get(1).getEndTokenIndex()).isEqualTo(7);
    assertThat(duplicationBlocks.get(0).getHashList()).isNotEmpty();

    assertThat(duplicationBlocks.get(2).getStartLine()).isEqualTo(3);
    assertThat(duplicationBlocks.get(2).getEndLine()).isEqualTo(7);
    assertThat(duplicationBlocks.get(2).getStartTokenIndex()).isEqualTo(4);
    assertThat(duplicationBlocks.get(2).getEndTokenIndex()).isEqualTo(8);
    assertThat(duplicationBlocks.get(0).getHashList()).isNotEmpty();
  }

  // SONAR-6000
  @Test
  public void truncateDuplication() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    String duplicatedStuff = "Sample xoo\n";

    int blockCount = 10000;
    File xooFile1 = new File(srcDir, "sample.xoo");
    for (int i = 0; i < blockCount; i++) {
      FileUtils.write(xooFile1, duplicatedStuff, true);
      FileUtils.write(xooFile1, "" + i + "\n", true);
    }

    TaskResult result = tester.newTask()
      .properties(builder
        .put("sonar.sources", "src")
        .put("sonar.cpd.xoo.minimumTokens", "1")
        .put("sonar.cpd.xoo.minimumLines", "1")
        .build())
      .start();

    Map<String, List<Measure>> allMeasures = result.allMeasures();

    assertThat(allMeasures.get("com.foo.project")).extracting("metricKey", "intValue", "doubleValue", "stringValue").containsOnly(
      Tuple.tuple(CoreMetrics.QUALITY_PROFILES_KEY, 0, 0.0,
        "[{\"key\":\"Sonar Way\",\"language\":\"xoo\",\"name\":\"Sonar Way\",\"rulesUpdatedAt\":\"2009-02-13T23:31:31+0000\"}]"));

    assertThat(allMeasures.get("com.foo.project:src/sample.xoo")).extracting("metricKey", "intValue").containsOnly(
      Tuple.tuple(CoreMetrics.LINES_KEY, blockCount * 2 + 1),
      Tuple.tuple(CoreMetrics.DUPLICATED_FILES_KEY, 1),
      Tuple.tuple(CoreMetrics.DUPLICATED_BLOCKS_KEY, blockCount),
      Tuple.tuple(CoreMetrics.DUPLICATED_LINES_KEY, blockCount));

    List<org.sonar.batch.protocol.output.BatchReport.Duplication> duplicationGroups = result.duplicationsFor(result.inputFile("src/sample.xoo"));
    assertThat(duplicationGroups).hasSize(1);

    org.sonar.batch.protocol.output.BatchReport.Duplication cloneGroup = duplicationGroups.get(0);
    assertThat(cloneGroup.getDuplicateList()).hasSize(100);
  }

  @Test
  public void testIntraFileDuplications() throws IOException {
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    String content = "Sample xoo\ncontent\nfoo\nbar\nSample xoo\ncontent\n";

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, content);

    TaskResult result = tester.newTask()
      .properties(builder
        .put("sonar.sources", "src")
        .put("sonar.cpd.xoo.minimumTokens", "2")
        .put("sonar.cpd.xoo.minimumLines", "2")
        .put("sonar.verbose", "true")
        .build())
      .start();

    Map<String, List<org.sonar.batch.protocol.output.BatchReport.Measure>> allMeasures = result.allMeasures();
    assertThat(allMeasures.get("com.foo.project:src/sample.xoo")).extracting("metricKey", "intValue")
      .contains(tuple("duplicated_blocks", 2),
        tuple("duplicated_files", 1),
        tuple("duplicated_lines", 4));

    InputFile inputFile = result.inputFile("src/sample.xoo");
    // One clone group
    List<org.sonar.batch.protocol.output.BatchReport.Duplication> duplicationGroups = result.duplicationsFor(inputFile);
    assertThat(duplicationGroups).hasSize(1);

    org.sonar.batch.protocol.output.BatchReport.Duplication cloneGroup = duplicationGroups.get(0);
    assertThat(cloneGroup.getOriginPosition().getStartLine()).isEqualTo(1);
    assertThat(cloneGroup.getOriginPosition().getEndLine()).isEqualTo(2);
    assertThat(cloneGroup.getDuplicateList()).hasSize(1);
    assertThat(cloneGroup.getDuplicate(0).getRange().getStartLine()).isEqualTo(5);
    assertThat(cloneGroup.getDuplicate(0).getRange().getEndLine()).isEqualTo(6);
  }

}
