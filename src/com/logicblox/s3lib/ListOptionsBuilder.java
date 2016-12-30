package com.logicblox.s3lib;

public class ListOptionsBuilder {
  
  private String bucket;
  private String objectKey;
  private boolean recursive;
  private boolean includeVersions;
  private boolean excludeDirs;
  
  public ListOptionsBuilder setBucket(String bucket) {
    this.bucket = bucket;
    return this;
  }
  
  public ListOptionsBuilder setObjectKey(String objectKey) {
    this.objectKey = objectKey;
    return this;
  }
  
  public ListOptionsBuilder setRecursive(boolean recursive) {
    this.recursive = recursive;
    return this;
  }
  
  public ListOptionsBuilder setIncludeVersions(boolean includeVersions) {
    this.includeVersions = includeVersions;
    return this;
  }
  
  public ListOptionsBuilder setExcludeDirs(boolean excludeDirs) {
    this.excludeDirs = excludeDirs;
    return this;
  }
  
  public ListOptions createListOptions() {
    return new ListOptions(bucket, objectKey, recursive, includeVersions, excludeDirs);
  }
  
}