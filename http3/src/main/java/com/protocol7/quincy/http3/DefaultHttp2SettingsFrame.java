/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.protocol7.quincy.http3;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.UnstableApi;

/** The default {@link Http2SettingsFrame} implementation. */
@UnstableApi
public class DefaultHttp2SettingsFrame implements Http2SettingsFrame {

  private final Http2Settings settings;

  public DefaultHttp2SettingsFrame(final Http2Settings settings) {
    this.settings = ObjectUtil.checkNotNull(settings, "settings");
  }

  @Override
  public Http2Settings settings() {
    return settings;
  }

  @Override
  public String name() {
    return "SETTINGS";
  }

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof Http2SettingsFrame)) {
      return false;
    }
    final Http2SettingsFrame other = (Http2SettingsFrame) o;
    return settings.equals(other.settings());
  }

  @Override
  public int hashCode() {
    return settings.hashCode();
  }

  @Override
  public String toString() {
    return StringUtil.simpleClassName(this) + "(settings=" + settings + ')';
  }
}
