package com.logicblox.s3lib;


import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class UploadDirectoryCommand extends Command
{
  private ListeningExecutorService _httpExecutor;
  private ListeningScheduledExecutorService _executor;
  private S3Client _client;

  public UploadDirectoryCommand(
          ListeningExecutorService httpExecutor,
          ListeningScheduledExecutorService internalExecutor,
          S3Client client
  )
  {
    _httpExecutor = httpExecutor;
    _executor = internalExecutor;
    _client = client;
  }

  public ListenableFuture<?> run(final File f, final String bucket, final String object, final String encKey) throws ExecutionException, InterruptedException, IOException {
    Collection<File> found = FileUtils.listFiles(f, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);

    List<ListenableFuture<?>> files = new ArrayList<ListenableFuture<?>>();
    for (File uf : found) {
      String relPath = uf.getPath().substring(f.getPath().length()+1);
      String key = object + "/" + relPath;
      files.add(_client.upload(uf, bucket, key, encKey));
    }

    return Futures.transform(Futures.allAsList(files), Functions.constant(null));
  }

}
