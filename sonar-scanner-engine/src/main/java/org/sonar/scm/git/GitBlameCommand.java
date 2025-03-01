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
package org.sonar.scm.git;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.Collections.emptyList;
import static org.sonar.api.utils.Preconditions.checkState;

public class GitBlameCommand {
  private static final Logger LOG = Loggers.get(GitBlameCommand.class);
  private static final Pattern EMAIL_PATTERN = Pattern.compile("<(\\S*?)>");
  private static final String COMMITTER_TIME = "committer-time ";
  private static final String COMMITTER_MAIL = "committer-mail ";

  private static final String DEFAULT_GIT_COMMAND = "git";
  private static final String BLAME_COMMAND = "blame";
  private static final String BLAME_LINE_PORCELAIN_FLAG = "--line-porcelain";
  private static final String IGNORE_WHITESPACES = "-w";

  private final System2 system;
  private final ProcessWrapperFactory processWrapperFactory;
  private String gitCommand;

  @Autowired
  public GitBlameCommand(System2 system, ProcessWrapperFactory processWrapperFactory) {
    this.system = system;
    this.processWrapperFactory = processWrapperFactory;
  }

  GitBlameCommand(String gitCommand, System2 system, ProcessWrapperFactory processWrapperFactory) {
    this.gitCommand = gitCommand;
    this.system = system;
    this.processWrapperFactory = processWrapperFactory;
  }

  /**
   * This method must be executed before org.sonar.scm.git.GitBlameCommand#blame
   *
   * @return true, if native git is installed
   */
  public boolean checkIfEnabled() {
    try {
      this.gitCommand = locateDefaultGit();
      MutableString stdOut = new MutableString();
      this.processWrapperFactory.create(null, l -> stdOut.string = l, gitCommand, "--version").execute();
      return stdOut.string != null && stdOut.string.startsWith("git version");
    } catch (Exception e) {
      LOG.debug("Failed to find git native client", e);
      return false;
    }
  }

  private String locateDefaultGit() throws IOException {
    if (this.gitCommand != null) {
      return this.gitCommand;
    }
    // if not set fall back to defaults
    if (system.isOsWindows()) {
      return locateGitOnWindows();
    }
    return DEFAULT_GIT_COMMAND;
  }

  private String locateGitOnWindows() throws IOException {
    // Windows will search current directory in addition to the PATH variable, which is unsecure.
    // To avoid it we use where.exe to find git binary only in PATH.
    LOG.debug("Looking for git command in the PATH using where.exe (Windows)");
    List<String> whereCommandResult = new LinkedList<>();
    this.processWrapperFactory.create(null, whereCommandResult::add, "C:\\Windows\\System32\\where.exe", "$PATH:git.exe")
      .execute();

    if (!whereCommandResult.isEmpty()) {
      String out = whereCommandResult.get(0).trim();
      LOG.debug("Found git.exe at {}", out);
      return out;
    }
    throw new IllegalStateException("git.exe not found in PATH. PATH value was: " + system.property("PATH"));
  }

  public List<BlameLine> blame(Path baseDir, String fileName) throws Exception {
    BlameOutputProcessor outputProcessor = new BlameOutputProcessor();
    try {
      this.processWrapperFactory.create(baseDir, outputProcessor::process, gitCommand, BLAME_COMMAND, BLAME_LINE_PORCELAIN_FLAG, IGNORE_WHITESPACES, fileName)
        .execute();
    } catch (UncommittedLineException e) {
      LOG.debug("Unable to blame file '{}' - it has uncommitted changes", fileName);
      return emptyList();
    }
    return outputProcessor.getBlameLines();
  }

  private static class BlameOutputProcessor {
    private final List<BlameLine> blameLines = new LinkedList<>();
    private String sha1 = null;
    private String committerTime = null;
    private String committerMail = null;

    public List<BlameLine> getBlameLines() {
      return blameLines;
    }

    public void process(String line) {
      if (sha1 == null) {
        sha1 = line.split(" ")[0];
      } else if (line.startsWith("\t")) {
        saveEntry();
      } else if (line.startsWith(COMMITTER_TIME)) {
        committerTime = line.substring(COMMITTER_TIME.length());
      } else if (line.startsWith(COMMITTER_MAIL)) {
        Matcher matcher = EMAIL_PATTERN.matcher(line);
        if (!matcher.find(COMMITTER_MAIL.length()) || matcher.groupCount() != 1) {
          throw new IllegalStateException("Couldn't parse committer email from: " + line);
        }
        committerMail = matcher.group(1);
        if (committerMail.equals("not.committed.yet")) {
          throw new UncommittedLineException();
        }
      }
    }

    private void saveEntry() {
      checkState(committerMail != null, "Did not find a committer email for an entry");
      checkState(committerTime != null, "Did not find a committer time for an entry");
      checkState(sha1 != null, "Did not find a commit sha1 for an entry");
      try {
        blameLines.add(new BlameLine()
          .revision(sha1)
          .author(committerMail)
          .date(Date.from(Instant.ofEpochSecond(Long.parseLong(committerTime)))));
      } catch (NumberFormatException e) {
        throw new IllegalStateException("Invalid committer time found: " + committerTime);
      }
      committerMail = null;
      sha1 = null;
      committerTime = null;
    }
  }

  private static class MutableString {
    String string;
  }

  private static class UncommittedLineException extends RuntimeException {

  }
}
