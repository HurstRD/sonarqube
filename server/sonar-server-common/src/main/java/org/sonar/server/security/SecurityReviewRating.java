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
package org.sonar.server.security;

import java.util.Optional;
import javax.annotation.Nullable;
import org.sonar.server.measure.Rating;

import static org.sonar.server.measure.Rating.A;
import static org.sonar.server.measure.Rating.B;
import static org.sonar.server.measure.Rating.C;
import static org.sonar.server.measure.Rating.D;
import static org.sonar.server.measure.Rating.E;

public class SecurityReviewRating {

  private SecurityReviewRating() {
    // Only static method
  }

  public static Optional<Double> computePercent(long hotspotsToReview, long hotspotsReviewed) {
    long total = hotspotsToReview + hotspotsReviewed;
    if (total == 0) {
      return Optional.empty();
    }
    return Optional.of(hotspotsReviewed * 100.0 / total);
  }

  public static Rating computeRating(@Nullable Double percent) {
    if (percent == null || percent >= 80.0) {
      return A;
    } else if (percent >= 70.0) {
      return B;
    } else if (percent >= 50.0) {
      return C;
    } else if (percent >= 30.0) {
      return D;
    }
    return E;
  }
}
