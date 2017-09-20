package com.logicblox.s3lib;

import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class ListCommand extends Command {

  private ListOptions _options;

  public ListCommand(ListOptions options) {
    super(options);
    _options = options;
  }

  public ListenableFuture<List<S3File>> run() {
    ListenableFuture<List<S3File>> future =
        executeWithRetry(_client.getInternalExecutor(), new Callable<ListenableFuture<List<S3File>>>() {
          public ListenableFuture<List<S3File>> call() {
            return runActual();
          }
          
          public String toString() {
            return "listing objects and directories for "
                + getUri(_options.getBucketName(), _options.getObjectKey().orElse(""));
          }
        });
    
    return future;
  }
  
  private ListenableFuture<List<S3File>> runActual() {
    return _client.getApiExecutor().submit(new Callable<List<S3File>>() {

      public List<S3File> call() {
        ListObjectsRequest req = new ListObjectsRequest()
            .withBucketName(_options.getBucketName())
            .withPrefix(_options.getObjectKey().orElse(null));
        if (! _options.isRecursive()) {
          req.setDelimiter("/");
        }

        List<S3File> all = new ArrayList<S3File>();
        ObjectListing current = getAmazonS3Client().listObjects(req);
        appendS3ObjectSummaryList(all, current.getObjectSummaries());
        if (! _options.dirsExcluded()) {
          appendS3DirStringList(all, current.getCommonPrefixes(), _options.getBucketName());
        }
        current = getAmazonS3Client().listNextBatchOfObjects(current);
        
        while (current.isTruncated()) {
          appendS3ObjectSummaryList(all, current.getObjectSummaries());
          if (! _options.dirsExcluded()) {
            appendS3DirStringList(all, current.getCommonPrefixes(), _options.getBucketName());
          }
          current = getAmazonS3Client().listNextBatchOfObjects(current);
        }
        appendS3ObjectSummaryList(all, current.getObjectSummaries());
        if (! _options.dirsExcluded()) {
          appendS3DirStringList(all, current.getCommonPrefixes(), _options.getBucketName());
        }
        
        return all;
      }
    });
  }
  
  private List<S3File> appendS3ObjectSummaryList(
      List<S3File> all,
      List<S3ObjectSummary> appendList) {
    for (S3ObjectSummary o : appendList) {
      all.add(S3ObjectSummaryToS3File(o));
    }
    
    return all;
  }
  
  private List<S3File> appendS3DirStringList(
      List<S3File> all,
      List<String> appendList,
      String bucket) {
    for (String o : appendList) {
      all.add(S3DirStringToS3File(o, bucket));
    }
    
    return all;
  }
  
  private S3File S3ObjectSummaryToS3File(S3ObjectSummary o) {
    S3File of = new S3File();
    of.setKey(o.getKey());
    of.setETag(o.getETag());
    of.setBucketName(o.getBucketName());
    of.setSize(o.getSize());
    return of;
  }
  
  private S3File S3DirStringToS3File(String dir, String bucket) {
    S3File df = new S3File();
    df.setKey(dir);
    df.setBucketName(bucket);
    df.setSize(new Long(0));
    
    return df;
  }
}
