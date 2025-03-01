/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.hotspot.ws;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.assertj.core.groups.Tuple;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.sonar.api.issue.Issue;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.util.UuidFactory;
import org.sonar.core.util.UuidFactoryFast;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.protobuf.DbCommons;
import org.sonar.db.protobuf.DbIssues;
import org.sonar.db.rule.RuleDescriptionSectionDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserTesting;
import org.sonar.server.es.EsTester;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.issue.AvatarResolver;
import org.sonar.server.issue.AvatarResolverImpl;
import org.sonar.server.issue.IssueChangeWSSupport;
import org.sonar.server.issue.IssueChangeWSSupport.FormattingContext;
import org.sonar.server.issue.IssueChangeWSSupport.Load;
import org.sonar.server.issue.TextRangeResponseFormatter;
import org.sonar.server.issue.ws.UserResponseFormatter;
import org.sonar.server.security.SecurityStandards;
import org.sonar.server.security.SecurityStandards.SQCategory;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.Common;
import org.sonarqube.ws.Common.Changelog.Diff;
import org.sonarqube.ws.Common.Location;
import org.sonarqube.ws.Common.User;
import org.sonarqube.ws.Hotspots;

import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.api.rules.RuleType.SECURITY_HOTSPOT;
import static org.sonar.api.server.rule.RuleDescriptionSection.RuleDescriptionSectionKeys.ASSESS_THE_PROBLEM_SECTION_KEY;
import static org.sonar.api.server.rule.RuleDescriptionSection.RuleDescriptionSectionKeys.HOW_TO_FIX_SECTION_KEY;
import static org.sonar.api.server.rule.RuleDescriptionSection.RuleDescriptionSectionKeys.INTRODUCTION_SECTION_KEY;
import static org.sonar.api.server.rule.RuleDescriptionSection.RuleDescriptionSectionKeys.RESOURCES_SECTION_KEY;
import static org.sonar.api.server.rule.RuleDescriptionSection.RuleDescriptionSectionKeys.ROOT_CAUSE_SECTION_KEY;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.rule.RuleDescriptionSectionDto.DEFAULT_KEY;
import static org.sonar.db.rule.RuleDto.Format.HTML;
import static org.sonar.db.rule.RuleDto.Format.MARKDOWN;

@RunWith(DataProviderRunner.class)
public class ShowActionTest {
  private static final Random RANDOM = new Random();

  @Rule
  public DbTester dbTester = DbTester.create(System2.INSTANCE);
  @Rule
  public EsTester es = EsTester.create();
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();

  private final DbClient dbClient = dbTester.getDbClient();
  private final AvatarResolver avatarResolver = new AvatarResolverImpl();
  private final HotspotWsResponseFormatter responseFormatter = new HotspotWsResponseFormatter();
  private final IssueChangeWSSupport issueChangeSupport = Mockito.mock(IssueChangeWSSupport.class);
  private final HotspotWsSupport hotspotWsSupport = new HotspotWsSupport(dbClient, userSessionRule, System2.INSTANCE);
  private final UserResponseFormatter userFormatter = new UserResponseFormatter(new AvatarResolverImpl());
  private final TextRangeResponseFormatter textRangeFormatter = new TextRangeResponseFormatter();
  private final ShowAction underTest = new ShowAction(dbClient, hotspotWsSupport, responseFormatter, textRangeFormatter, userFormatter, issueChangeSupport);
  private final WsActionTester actionTester = new WsActionTester(underTest);
  private final UuidFactory uuidFactory = UuidFactoryFast.getInstance();

  @Test
  public void ws_is_public() {
    assertThat(actionTester.getDef().isInternal()).isFalse();
  }

  @Test
  public void fails_with_IAE_if_parameter_hotspot_is_missing() {
    TestRequest request = actionTester.newRequest();

    assertThatThrownBy(request::execute)
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("The 'hotspot' parameter is missing");
  }

  @Test
  public void fails_with_NotFoundException_if_hotspot_does_not_exist() {
    String key = randomAlphabetic(12);
    TestRequest request = actionTester.newRequest()
      .setParam("hotspot", key);

    assertThatThrownBy(request::execute)
      .isInstanceOf(NotFoundException.class)
      .hasMessage("Hotspot '%s' does not exist", key);
  }

  @Test
  @UseDataProvider("ruleTypesButHotspot")
  public void fails_with_NotFoundException_if_issue_is_not_a_hotspot(RuleType ruleType) {
    ComponentDto project = dbTester.components().insertPublicProject();
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(ruleType);
    IssueDto notAHotspot = dbTester.issues().insertIssue(rule, project, file, i -> i.setType(ruleType));
    TestRequest request = newRequest(notAHotspot);

    assertThatThrownBy(request::execute)
      .isInstanceOf(NotFoundException.class)
      .hasMessage("Hotspot '%s' does not exist", notAHotspot.getKey());
  }

  @DataProvider
  public static Object[][] ruleTypesButHotspot() {
    return Arrays.stream(RuleType.values())
      .filter(t -> t != SECURITY_HOTSPOT)
      .map(t -> new Object[] {t})
      .toArray(Object[][]::new);
  }

  @Test
  public void fails_with_NotFoundException_if_issue_is_hotspot_is_closed() {
    ComponentDto project = dbTester.components().insertPublicProject();
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setStatus(Issue.STATUS_CLOSED));
    TestRequest request = newRequest(hotspot);

    assertThatThrownBy(request::execute)
      .isInstanceOf(NotFoundException.class)
      .hasMessage("Hotspot '%s' does not exist", hotspot.getKey());
  }

  @Test
  public void fails_with_ForbiddenException_if_project_is_private_and_not_allowed() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    TestRequest request = newRequest(hotspot);

    assertThatThrownBy(request::execute)
      .isInstanceOf(ForbiddenException.class)
      .hasMessage("Insufficient privileges");
  }

  @Test
  public void succeeds_on_hotspot_with_flow() {
    ComponentDto project = dbTester.components().insertPublicProject();
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    ComponentDto anotherFile = dbTester.components().insertComponent(newFileDto(project));
    DbIssues.Locations.Builder locations = DbIssues.Locations.newBuilder().addFlow(DbIssues.Flow.newBuilder().addAllLocation(Arrays.asList(
      DbIssues.Location.newBuilder()
        .setComponentId(file.uuid())
        .setMsg("FLOW MESSAGE")
        .setTextRange(DbCommons.TextRange.newBuilder()
          .setStartLine(1)
          .setEndLine(1)
          .setStartOffset(0)
          .setEndOffset(12)
          .build())
        .build(),
      DbIssues.Location.newBuilder()
        .setComponentId(anotherFile.uuid())
        .setMsg("ANOTHER FLOW MESSAGE")
        .setTextRange(DbCommons.TextRange.newBuilder()
          .setStartLine(1)
          .setEndLine(1)
          .setStartOffset(0)
          .setEndOffset(12)
          .build())
        .build(),
      DbIssues.Location.newBuilder()
        .setMsg("FLOW MESSAGE WITHOUT FILE UUID")
        .setTextRange(DbCommons.TextRange.newBuilder()
          .setStartLine(1)
          .setEndLine(1)
          .setStartOffset(0)
          .setEndOffset(12)
          .build())
        .build())));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    var hotspot = dbTester.issues().insertHotspot(rule, project, file, i -> i.setLocations(locations.build()));
    mockChangelogAndCommentsFormattingContext();

    userSessionRule.registerComponents(project);

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getKey()).isEqualTo(hotspot.getKey());
    assertThat(response.getFlowsCount()).isEqualTo(1);
    assertThat(response.getFlows(0).getLocationsList())
      .extracting(Location::getMsg, Location::getComponent)
      .containsExactlyInAnyOrder(
        tuple("FLOW MESSAGE", file.getKey()),
        tuple("ANOTHER FLOW MESSAGE", anotherFile.getKey()),
        tuple("FLOW MESSAGE WITHOUT FILE UUID", file.getKey()));
  }

  @Test
  public void succeeds_on_public_project() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getKey()).isEqualTo(hotspot.getKey());
  }

  @Test
  public void succeeds_on_private_project_with_permission() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getKey()).isEqualTo(hotspot.getKey());
  }

  @Test
  public void return_canChangeStatus_false_on_public_project_when_anonymous() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getCanChangeStatus()).isFalse();
  }

  @Test
  @UseDataProvider("allPublicProjectPermissionsButSECURITYHOTSPOT_ADMIN")
  public void return_canChangeStatus_false_on_public_project_when_authenticated_without_SECURITYHOTSPOT_ADMIN_permission(@Nullable String permission) {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.logIn().registerComponents(project);
    if (permission != null) {
      userSessionRule.addProjectPermission(permission, project);
    }
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getCanChangeStatus()).isFalse();
  }

  @Test
  @UseDataProvider("allPublicProjectPermissionsButSECURITYHOTSPOT_ADMIN")
  public void return_canChangeStatus_true_on_public_project_when_authenticated_with_SECURITYHOTSPOT_ADMIN_permission(@Nullable String permission) {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project)
      .addProjectPermission(UserRole.SECURITYHOTSPOT_ADMIN, project);
    if (permission != null) {
      userSessionRule.addProjectPermission(permission, project);
    }
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getCanChangeStatus()).isTrue();
  }

  @DataProvider
  public static Object[][] allPublicProjectPermissionsButSECURITYHOTSPOT_ADMIN() {
    return new Object[][] {
      {null}, // no permission
      {UserRole.ADMIN},
      {UserRole.SCAN},
      {UserRole.ISSUE_ADMIN}
    };
  }

  @Test
  @UseDataProvider("allPrivateProjectPermissionsButSECURITYHOTSPOT_ADMIN_and_USER")
  public void return_canChangeStatus_false_on_private_project_without_SECURITYHOTSPOT_ADMIN_permission(@Nullable String permission) {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule
      .registerComponents(project)
      .logIn()
      .addProjectPermission(UserRole.USER, project);
    if (permission != null) {
      userSessionRule.addProjectPermission(permission, project);
    }
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getCanChangeStatus()).isFalse();
  }

  @Test
  @UseDataProvider("allPrivateProjectPermissionsButSECURITYHOTSPOT_ADMIN_and_USER")
  public void return_canChangeStatus_false_on_private_project_with_SECURITYHOTSPOT_ADMIN_permission(@Nullable String permission) {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule
      .registerComponents(project)
      .logIn()
      .addProjectPermission(UserRole.USER, project)
      .addProjectPermission(UserRole.SECURITYHOTSPOT_ADMIN, project);
    if (permission != null) {
      userSessionRule.addProjectPermission(permission, project);
    }
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getCanChangeStatus()).isTrue();
  }

  @DataProvider
  public static Object[][] allPrivateProjectPermissionsButSECURITYHOTSPOT_ADMIN_and_USER() {
    return new Object[][] {
      {null}, // only USER permission
      {UserRole.CODEVIEWER},
      {UserRole.ADMIN},
      {UserRole.SCAN},
      {UserRole.ISSUE_ADMIN}
    };
  }

  @Test
  @UseDataProvider("statusAndResolutionCombinations")
  public void returns_status_and_resolution(String status, @Nullable String resolution) {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setStatus(status).setResolution(resolution));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getStatus()).isEqualTo(status);
    if (resolution == null) {
      assertThat(response.hasResolution()).isFalse();
    } else {
      assertThat(response.getResolution()).isEqualTo(resolution);
    }
  }

  @DataProvider
  public static Object[][] statusAndResolutionCombinations() {
    return new Object[][] {
      {Issue.STATUS_TO_REVIEW, null},
      {Issue.STATUS_REVIEWED, Issue.RESOLUTION_FIXED},
      {Issue.STATUS_REVIEWED, Issue.RESOLUTION_SAFE}
    };
  }


  @Test
  public void dispatch_description_sections_of_advanced_rule_in_relevant_field() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));

    RuleDescriptionSectionDto introductionSection = generateSectionWithKey(INTRODUCTION_SECTION_KEY);
    RuleDescriptionSectionDto rootCauseSection = generateSectionWithKey(ROOT_CAUSE_SECTION_KEY);
    RuleDescriptionSectionDto assesTheProblemSection = generateSectionWithKey(ASSESS_THE_PROBLEM_SECTION_KEY);
    RuleDescriptionSectionDto resourcesSection = generateSectionWithKey(RESOURCES_SECTION_KEY);
    RuleDescriptionSectionDto howToFixSection = generateSectionWithKey(HOW_TO_FIX_SECTION_KEY);
    RuleDescriptionSectionDto dummySection = generateSectionWithKey("dummySection");

    RuleDto rule = newRuleWithoutSection(SECURITY_HOTSPOT,
      r -> r.addRuleDescriptionSectionDto(introductionSection)
        .addRuleDescriptionSectionDto(rootCauseSection)
        .addRuleDescriptionSectionDto(assesTheProblemSection)
        .addRuleDescriptionSectionDto(resourcesSection)
        .addRuleDescriptionSectionDto(howToFixSection)
        .addRuleDescriptionSectionDto(dummySection)
        .setDescriptionFormat(HTML));

    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getRule().getVulnerabilityDescription()).isEqualTo(rootCauseSection.getContent());
    assertThat(response.getRule().getRiskDescription()).isEqualTo(assesTheProblemSection.getContent());
    assertThat(response.getRule().getFixRecommendations()).isEqualTo(howToFixSection.getContent());
  }

  @Test
  public void fallbacks_to_default_section_in_case_of_legacy_rule() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));

    RuleDescriptionSectionDto introductionSection = generateSectionWithKey(DEFAULT_KEY);

    RuleDto rule = newRuleWithoutSection(SECURITY_HOTSPOT,
      r -> r.addRuleDescriptionSectionDto(introductionSection)
       .setDescriptionFormat(HTML));

    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getRule().getRiskDescription()).isEqualTo(introductionSection.getContent());
    assertThat(response.getRule().getVulnerabilityDescription()).isEmpty();
    assertThat(response.getRule().getFixRecommendations()).isEmpty();
  }

  @Test
  public void ignore_default_section_if_root_cause_provided() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));

    RuleDescriptionSectionDto introductionSection = generateSectionWithKey(INTRODUCTION_SECTION_KEY);
    RuleDescriptionSectionDto rootCauseSection = generateSectionWithKey(ROOT_CAUSE_SECTION_KEY);
    RuleDescriptionSectionDto assesTheProblemSection = generateSectionWithKey(ASSESS_THE_PROBLEM_SECTION_KEY);

    RuleDto rule = newRuleWithoutSection(SECURITY_HOTSPOT,
      r -> r.addRuleDescriptionSectionDto(introductionSection)
        .addRuleDescriptionSectionDto(rootCauseSection)
        .addRuleDescriptionSectionDto(assesTheProblemSection)
        .setDescriptionFormat(HTML));

    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getRule().getVulnerabilityDescription()).isEqualTo(rootCauseSection.getContent());
    assertThat(response.getRule().getRiskDescription()).isEqualTo(assesTheProblemSection.getContent());
    assertThat(response.getRule().getFixRecommendations()).isEmpty();
  }

  private RuleDescriptionSectionDto generateSectionWithKey(String assessTheProblemSectionKey) {
    return RuleDescriptionSectionDto.builder()
      .uuid(uuidFactory.create())
      .key(assessTheProblemSectionKey)
      .content(randomAlphabetic(200))
      .build();
  }

  @Test
  public void returns_html_description_for_custom_rules() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));

    String description = "== Title\n<div>line1\nline2</div>";

    RuleDescriptionSectionDto sectionDto = RuleDescriptionSectionDto.builder()
      .uuid(uuidFactory.create())
      .key(ASSESS_THE_PROBLEM_SECTION_KEY)
      .content(description)
      .build();

    RuleDto rule = newRuleWithoutSection(SECURITY_HOTSPOT,
      r -> r.setTemplateUuid("123")
        .addRuleDescriptionSectionDto(sectionDto)
        .setDescriptionFormat(MARKDOWN));

    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getRule().getRiskDescription()).isEqualTo("<h2>Title</h2>&lt;div&gt;line1<br/>line2&lt;/div&gt;");
  }


  @Test
  public void handles_null_description_for_custom_rules() {
    ComponentDto project = dbTester.components().insertPrivateProject();
    userSessionRule.registerComponents(project);
    userSessionRule.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));

    RuleDto rule = newRuleWithoutSection(SECURITY_HOTSPOT, r -> r.setTemplateUuid("123"));

    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getRule().getRiskDescription()).isNullOrEmpty();
  }

  @Test
  public void returns_hotspot_component_and_rule() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getKey()).isEqualTo(hotspot.getKey());
    verifyComponent(response.getComponent(), file, null, null);
    verifyComponent(response.getProject(), project, null, null);
    verifyRule(response.getRule(), rule);
    assertThat(response.hasTextRange()).isFalse();
  }

  @Test
  public void returns_no_textRange_when_locations_have_none() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder().build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.hasTextRange()).isFalse();
  }

  @Test
  @UseDataProvider("randomTextRangeValues")
  public void returns_textRange(int startLine, int endLine, int startOffset, int endOffset) {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder()
          .setStartLine(startLine)
          .setEndLine(endLine)
          .setStartOffset(startOffset)
          .setEndOffset(endOffset)
          .build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.hasTextRange()).isTrue();
    Common.TextRange textRange = response.getTextRange();
    assertThat(textRange.getStartLine()).isEqualTo(startLine);
    assertThat(textRange.getEndLine()).isEqualTo(endLine);
    assertThat(textRange.getStartOffset()).isEqualTo(startOffset);
    assertThat(textRange.getEndOffset()).isEqualTo(endOffset);
  }

  @Test
  public void returns_no_assignee_when_user_does_not_exist() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAssigneeUuid(randomAlphabetic(10)));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.hasAssignee()).isFalse();
  }

  @Test
  public void returns_assignee_details_when_user_exists() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto assignee = dbTester.users().insertUser();
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAssigneeUuid(assignee.getUuid()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getAssignee()).isEqualTo(assignee.getLogin());
    assertThat(response.getUsersList()).hasSize(1);
    User wsAssignee = response.getUsersList().iterator().next();
    assertThat(wsAssignee.getLogin()).isEqualTo(assignee.getLogin());
    assertThat(wsAssignee.getName()).isEqualTo(assignee.getName());
    assertThat(wsAssignee.getActive()).isEqualTo(assignee.isActive());
    assertThat(wsAssignee.getAvatar()).isEqualTo(avatarResolver.create(assignee));
  }

  @Test
  public void returns_no_avatar_if_assignee_has_no_email() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto assignee = dbTester.users().insertUser(t -> t.setEmail(null));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAssigneeUuid(assignee.getUuid()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList()).hasSize(1);
    assertThat(response.getUsersList().iterator().next().hasAvatar()).isFalse();
  }

  @Test
  public void returns_inactive_when_assignee_is_inactive() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto assignee = dbTester.users().insertUser(t -> t.setActive(false));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAssigneeUuid(assignee.getUuid()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList()).hasSize(1);
    assertThat(response.getUsersList().iterator().next().getActive()).isFalse();
  }

  @Test
  public void returns_author_login_when_user_does_not_exist() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    String authorLogin = randomAlphabetic(10);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAuthorLogin(authorLogin));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList()).isEmpty();
    assertThat(response.getAuthor()).isEqualTo(authorLogin);
  }

  @Test
  public void returns_author_details_when_user_exists() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto author = dbTester.users().insertUser();
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAuthorLogin(author.getLogin()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getAuthor()).isEqualTo(author.getLogin());
    User wsAuthorFromList = response.getUsersList().iterator().next();
    assertThat(wsAuthorFromList.getLogin()).isEqualTo(author.getLogin());
    assertThat(wsAuthorFromList.getName()).isEqualTo(author.getName());
    assertThat(wsAuthorFromList.getActive()).isEqualTo(author.isActive());
    assertThat(wsAuthorFromList.getAvatar()).isEqualTo(avatarResolver.create(author));
  }

  @Test
  public void returns_no_avatar_if_author_has_no_email() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto author = dbTester.users().insertUser(t -> t.setEmail(null));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAuthorLogin(author.getLogin()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList()).hasSize(1);
    assertThat(response.getUsersList().iterator().next().hasAvatar()).isFalse();
  }

  @Test
  public void returns_inactive_if_author_is_inactive() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    UserDto author = dbTester.users().insertUser(t -> t.setActive(false));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, t -> t.setAuthorLogin(author.getLogin()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList()).hasSize(1);
    assertThat(response.getUsersList().iterator().next().getActive()).isFalse();
  }

  @DataProvider
  public static Object[][] randomTextRangeValues() {
    int startLine = RANDOM.nextInt(200);
    int endLine = RANDOM.nextInt(200);
    int startOffset = RANDOM.nextInt(200);
    int endOffset = RANDOM.nextInt(200);
    return new Object[][] {
      {startLine, endLine, startOffset, endOffset}
    };
  }

  @Test
  public void returns_textRange_missing_fields() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.hasTextRange()).isTrue();
    Common.TextRange textRange = response.getTextRange();
    assertThat(textRange.hasStartLine()).isFalse();
    assertThat(textRange.hasEndLine()).isFalse();
    assertThat(textRange.hasStartOffset()).isFalse();
    assertThat(textRange.hasEndOffset()).isFalse();
  }

  @Test
  @UseDataProvider("allSQCategoryAndVulnerabilityProbability")
  public void returns_securityCategory_and_vulnerabilityProbability_of_rule(Set<String> standards,
    SQCategory expected) {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    RuleDto rule = newRule(SECURITY_HOTSPOT, t -> t.setSecurityStandards(standards));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    Hotspots.Rule wsRule = response.getRule();
    assertThat(wsRule.getSecurityCategory()).isEqualTo(expected.getKey());
    assertThat(wsRule.getVulnerabilityProbability()).isEqualTo(expected.getVulnerability().name());
  }

  @DataProvider
  public static Object[][] allSQCategoryAndVulnerabilityProbability() {
    Stream<Object[]> allButOthers = SecurityStandards.CWES_BY_SQ_CATEGORY
      .entrySet()
      .stream()
      .map(t -> new Object[] {
        t.getValue().stream().map(s -> "cwe:" + s).collect(Collectors.toSet()),
        t.getKey()
      });
    Stream<Object[]> others = Stream.of(
      new Object[] {Collections.emptySet(), SQCategory.OTHERS},
      new Object[] {ImmutableSet.of("foo", "bar", "acme"), SQCategory.OTHERS});
    return Stream.concat(allButOthers, others)
      .toArray(Object[][]::new);
  }

  @Test
  public void returns_project_twice_when_hotspot_on_project() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, project,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    verifyComponent(response.getProject(), project, null, null);
    verifyComponent(response.getComponent(), project, null, null);
  }

  @Test
  public void returns_branch_but_no_pullRequest_on_component_and_project_on_non_main_branch() {
    ComponentDto project = dbTester.components().insertPublicProject();
    ComponentDto branch = dbTester.components().insertProjectBranch(project);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(branch));
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, branch, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    verifyComponent(response.getProject(), branch, branch.getBranch(), null);
    verifyComponent(response.getComponent(), file, branch.getBranch(), null);
  }

  @Test
  public void returns_pullRequest_but_no_branch_on_component_and_project_on_pullRequest() {
    ComponentDto project = dbTester.components().insertPublicProject();
    ComponentDto pullRequest = dbTester.components().insertProjectBranch(project,
      t -> t.setBranchType(BranchType.PULL_REQUEST));
    ComponentDto file = dbTester.components().insertComponent(newFileDto(pullRequest));
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, pullRequest, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    mockChangelogAndCommentsFormattingContext();

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    verifyComponent(response.getProject(), pullRequest, null, pullRequest.getPullRequest());
    verifyComponent(response.getComponent(), file, null, pullRequest.getPullRequest());
  }

  @Test
  public void returns_hotspot_changelog_and_comments() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setLocations(DbIssues.Locations.newBuilder()
        .setTextRange(DbCommons.TextRange.newBuilder().build())
        .build()));
    List<Common.Changelog> changelog = IntStream.range(0, 1 + new Random().nextInt(12))
      .mapToObj(i -> Common.Changelog.newBuilder().setUser("u" + i).build())
      .collect(Collectors.toList());
    List<Common.Comment> comments = IntStream.range(0, 1 + new Random().nextInt(12))
      .mapToObj(i -> Common.Comment.newBuilder().setKey("u" + i).build())
      .collect(Collectors.toList());
    FormattingContext formattingContext = mockChangelogAndCommentsFormattingContext();
    when(issueChangeSupport.formatChangelog(any(), any())).thenReturn(changelog.stream());
    when(issueChangeSupport.formatComments(any(), any(), any())).thenReturn(comments.stream());

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getChangelogList())
      .extracting(Common.Changelog::getUser)
      .containsExactly(changelog.stream().map(Common.Changelog::getUser).toArray(String[]::new));
    assertThat(response.getCommentList())
      .extracting(Common.Comment::getKey)
      .containsExactly(comments.stream().map(Common.Comment::getKey).toArray(String[]::new));
    verify(issueChangeSupport).newFormattingContext(any(DbSession.class),
      argThat(new IssueDtoSetArgumentMatcher(hotspot)),
      eq(Load.ALL),
      eq(Collections.emptySet()), eq(ImmutableSet.of(project, file)));
    verify(issueChangeSupport).formatChangelog(argThat(new IssueDtoArgumentMatcher(hotspot)), eq(formattingContext));
    verify(issueChangeSupport).formatComments(argThat(new IssueDtoArgumentMatcher(hotspot)), any(Common.Comment.Builder.class), eq(formattingContext));
  }

  @Test
  public void returns_user_details_of_users_from_ChangelogAndComments() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file);
    FormattingContext formattingContext = mockChangelogAndCommentsFormattingContext();
    Set<UserDto> changeLogAndCommentsUsers = IntStream.range(0, 1 + RANDOM.nextInt(14))
      .mapToObj(i -> UserTesting.newUserDto())
      .collect(Collectors.toSet());
    when(formattingContext.getUsers()).thenReturn(changeLogAndCommentsUsers);

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList())
      .extracting(User::getLogin, User::getName, User::getActive)
      .containsExactlyInAnyOrder(
        changeLogAndCommentsUsers.stream()
          .map(t -> tuple(t.getLogin(), t.getName(), t.isActive()))
          .toArray(Tuple[]::new));
  }

  @Test
  public void returns_user_of_users_from_ChangelogAndComments_and_assignee_and_author() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    UserDto author = dbTester.users().insertUser();
    UserDto assignee = dbTester.users().insertUser();
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setAuthorLogin(author.getLogin())
        .setAssigneeUuid(assignee.getUuid()));
    FormattingContext formattingContext = mockChangelogAndCommentsFormattingContext();
    Set<UserDto> changeLogAndCommentsUsers = IntStream.range(0, 1 + RANDOM.nextInt(14))
      .mapToObj(i -> UserTesting.newUserDto())
      .collect(Collectors.toSet());
    when(formattingContext.getUsers()).thenReturn(changeLogAndCommentsUsers);

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList())
      .extracting(User::getLogin, User::getName, User::getActive)
      .containsExactlyInAnyOrder(
        Stream.concat(
          Stream.of(author, assignee),
          changeLogAndCommentsUsers.stream())
          .map(t -> tuple(t.getLogin(), t.getName(), t.isActive()))
          .toArray(Tuple[]::new));
  }

  @Test
  public void do_not_duplicate_user_if_author_assignee_ChangeLogComment_user() {
    ComponentDto project = dbTester.components().insertPublicProject();
    userSessionRule.registerComponents(project);
    RuleDto rule = newRule(SECURITY_HOTSPOT);
    ComponentDto file = dbTester.components().insertComponent(newFileDto(project));
    UserDto author = dbTester.users().insertUser();
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file,
      t -> t.setAuthorLogin(author.getLogin())
        .setAssigneeUuid(author.getUuid()));
    FormattingContext formattingContext = mockChangelogAndCommentsFormattingContext();
    when(formattingContext.getUsers()).thenReturn(ImmutableSet.of(author));

    Hotspots.ShowWsResponse response = newRequest(hotspot)
      .executeProtobuf(Hotspots.ShowWsResponse.class);

    assertThat(response.getUsersList())
      .extracting(User::getLogin, User::getName, User::getActive)
      .containsOnly(tuple(author.getLogin(), author.getName(), author.isActive()));
  }

  @Test
  public void verify_response_example() {
    ComponentDto project = dbTester.components().insertPublicProject(componentDto -> componentDto
      .setName("test-project")
      .setLongName("test-project")
      .setDbKey("com.sonarsource:test-project"));
    userSessionRule.registerComponents(project)
      .addProjectPermission(UserRole.SECURITYHOTSPOT_ADMIN, project);

    ComponentDto file = dbTester.components().insertComponent(
      newFileDto(project)
        .setDbKey("com.sonarsource:test-project:src/main/java/com/sonarsource/FourthClass.java")
        .setName("FourthClass.java")
        .setLongName("src/main/java/com/sonarsource/FourthClass.java")
        .setPath("src/main/java/com/sonarsource/FourthClass.java"));
    UserDto author = dbTester.users().insertUser(u -> u.setLogin("joe")
      .setName("Joe"));

    long time = 1577976190000L;
    RuleDto rule = newRule(SECURITY_HOTSPOT, r -> r.setRuleKey("S4787")
      .setRepositoryKey("java")
      .setName("rule-name")
      .setSecurityStandards(Sets.newHashSet(SQCategory.WEAK_CRYPTOGRAPHY.getKey())));
    IssueDto hotspot = dbTester.issues().insertHotspot(rule, project, file, h -> h
      .setAssigneeUuid("assignee-uuid")
      .setAuthorLogin("joe")
      .setMessage("message")
      .setLine(10)
      .setChecksum("a227e508d6646b55a086ee11d63b21e9")
      .setIssueCreationTime(time)
      .setIssueUpdateTime(time)
      .setAuthorLogin(author.getLogin())
      .setAssigneeUuid(author.getUuid())
      .setKee("AW9mgJw6eFC3pGl94Wrf"));

    List<Common.Changelog> changelog = IntStream.range(0, 3)
      .mapToObj(i -> Common.Changelog.newBuilder()
        .setUser("joe")
        .setCreationDate("2020-01-02T14:44:55+0100")
        .addDiffs(Diff.newBuilder().setKey("diff-key-" + i).setNewValue("new-value-" + i).setOldValue("old-value-" + i))
        .setIsUserActive(true)
        .setUserName("Joe")
        .setAvatar("my-avatar")
        .build())
      .collect(Collectors.toList());
    List<Common.Comment> comments = IntStream.range(0, 3)
      .mapToObj(i -> Common.Comment.newBuilder()
        .setKey("comment-" + i)
        .setHtmlText("html text " + i)
        .setLogin("Joe")
        .setMarkdown("markdown " + i)
        .setCreatedAt("2020-01-02T14:47:47+0100")
        .build())
      .collect(Collectors.toList());

    mockChangelogAndCommentsFormattingContext();
    when(issueChangeSupport.formatChangelog(any(), any())).thenReturn(changelog.stream());
    when(issueChangeSupport.formatComments(any(), any(), any())).thenReturn(comments.stream());

    assertThat(actionTester.getDef().responseExampleAsString()).isNotNull();
    newRequest(hotspot)
      .execute()
      .assertJson(actionTester.getDef().responseExampleAsString());
  }

  private FormattingContext mockChangelogAndCommentsFormattingContext() {
    FormattingContext formattingContext = Mockito.mock(FormattingContext.class);
    when(issueChangeSupport.newFormattingContext(any(), any(), any(), anySet(), anySet())).thenReturn(formattingContext);
    return formattingContext;
  }

  private void verifyRule(Hotspots.Rule wsRule, RuleDto dto) {
    assertThat(wsRule.getKey()).isEqualTo(dto.getKey().toString());
    assertThat(wsRule.getName()).isEqualTo(dto.getName());
    assertThat(wsRule.getSecurityCategory()).isEqualTo(SQCategory.OTHERS.getKey());
    assertThat(wsRule.getVulnerabilityProbability()).isEqualTo(SQCategory.OTHERS.getVulnerability().name());
  }

  private static void verifyComponent(Hotspots.Component wsComponent, ComponentDto dto, @Nullable String branch, @Nullable String pullRequest) {
    assertThat(wsComponent.getKey()).isEqualTo(dto.getKey());
    if (dto.path() == null) {
      assertThat(wsComponent.hasPath()).isFalse();
    } else {
      assertThat(wsComponent.getPath()).isEqualTo(dto.path());
    }
    assertThat(wsComponent.getQualifier()).isEqualTo(dto.qualifier());
    assertThat(wsComponent.getName()).isEqualTo(dto.name());
    assertThat(wsComponent.getLongName()).isEqualTo(dto.longName());
    if (branch == null) {
      assertThat(wsComponent.hasBranch()).isFalse();
    } else {
      assertThat(wsComponent.getBranch()).isEqualTo(branch);
    }
    if (pullRequest == null) {
      assertThat(wsComponent.hasPullRequest()).isFalse();
    } else {
      assertThat(wsComponent.getPullRequest()).isEqualTo(pullRequest);
    }
  }

  private TestRequest newRequest(IssueDto hotspot) {
    return actionTester.newRequest()
      .setParam("hotspot", hotspot.getKey());
  }

  private RuleDto newRule(RuleType ruleType) {
    return newRule(ruleType, t -> {
    });
  }

  private RuleDto newRule(RuleType ruleType, Consumer<RuleDto> populate) {
    return newRule(ruleType, RuleTesting::newRule, populate);
  }

  private RuleDto newRuleWithoutSection(RuleType ruleType, Consumer<RuleDto> populate) {
    return newRule(ruleType, RuleTesting::newRuleWithoutDescriptionSection, populate);
  }

  private RuleDto newRule(RuleType ruleType, Supplier<RuleDto> ruleDefinitionDtoSupplier, Consumer<RuleDto> populate) {
    RuleDto ruleDefinitionDto = ruleDefinitionDtoSupplier.get().setType(ruleType);
    populate.accept(ruleDefinitionDto);
    dbTester.rules().insert(ruleDefinitionDto);
    return ruleDefinitionDto;
  }

  private static class IssueDtoSetArgumentMatcher implements ArgumentMatcher<Set<IssueDto>> {
    private final IssueDto expected;

    private IssueDtoSetArgumentMatcher(IssueDto expected) {
      this.expected = expected;
    }

    @Override
    public boolean matches(Set<IssueDto> argument) {
      return argument != null && argument.size() == 1 && argument.iterator().next().getKey().equals(expected.getKey());
    }

    @Override
    public String toString() {
      return "Set<IssueDto>[" + expected.getKey() + "]";
    }
  }

  private static class IssueDtoArgumentMatcher implements ArgumentMatcher<IssueDto> {
    private final IssueDto expected;

    private IssueDtoArgumentMatcher(IssueDto expected) {
      this.expected = expected;
    }

    @Override
    public boolean matches(IssueDto argument) {
      return argument != null && argument.getKey().equals(expected.getKey());
    }

    @Override
    public String toString() {
      return "IssueDto[key=" + expected.getKey() + "]";
    }
  }

}
