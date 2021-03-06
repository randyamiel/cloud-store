/*
  Copyright 2018, Infor Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package com.logicblox.cloudstore;

public class ProgressOptionsBuilder
{
  private String _objectUri;
  private String _operation;
  private long _fileSizeInBytes;

  public ProgressOptionsBuilder setObjectUri(String objectUri)
  {
    _objectUri = objectUri;
    return this;
  }

  public ProgressOptionsBuilder setOperation(String operation)
  {
    _operation = operation;
    return this;
  }

  public ProgressOptionsBuilder setFileSizeInBytes(long fileSizeInBytes)
  {
    _fileSizeInBytes = fileSizeInBytes;
    return this;
  }

  public ProgressOptions createProgressOptions()
  {
    return new ProgressOptions(_objectUri, _operation, _fileSizeInBytes);
  }
}
