/*
 * Copyright 2025 QingWan (qingwanmail@foxmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package qing.albatross.manager.model;

/**
 * 服务组件信息模型类，包含版本描述信息
 */
public class ServerInfo {
  private String version;
  private String description; // 新增：版本描述信息
  private String primaryArchitecture;
  private boolean support32Bit;
  private String serverPath;
  private String libPath;
  private String lib32Path;
  private String agentPath;
  private long importTime; // 导入时间戳

  public ServerInfo() {}

  // Getters and Setters
  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  // 新增：描述信息的getter和setter
  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getPrimaryArchitecture() {
    return primaryArchitecture;
  }

  public void setPrimaryArchitecture(String primaryArchitecture) {
    this.primaryArchitecture = primaryArchitecture;
  }

  public boolean isSupport32Bit() {
    return support32Bit;
  }

  public void setSupport32Bit(boolean support32Bit) {
    this.support32Bit = support32Bit;
  }

  public String getServerPath() {
    return serverPath;
  }

  public void setServerPath(String serverPath) {
    this.serverPath = serverPath;
  }

  public String getLibPath() {
    return libPath;
  }

  public void setLibPath(String libPath) {
    this.libPath = libPath;
  }

  public String getLib32Path() {
    return lib32Path;
  }

  public void setLib32Path(String lib32Path) {
    this.lib32Path = lib32Path;
  }

  public String getAgentPath() {
    return agentPath;
  }

  public void setAgentPath(String agentPath) {
    this.agentPath = agentPath;
  }

  public long getImportTime() {
    return importTime;
  }

  public void setImportTime(long importTime) {
    this.importTime = importTime;
  }
}
