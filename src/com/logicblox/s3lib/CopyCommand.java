package com.logicblox.s3lib;


import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class CopyCommand extends Command
{
  private CopyOptions _options;
  private OverallProgressListenerFactory _progressListenerFactory;

  public CopyCommand(CopyOptions options)
  {
    super(options);
    _options = options;

    _progressListenerFactory = options.getOverallProgressListenerFactory().orElse(null);
  }

  public ListenableFuture<S3File> run()
  {
    if(_options.isDryRun())
    {
      System.out.println("<DRYRUN> copying '" + getUri(
        _options.getSourceBucketName(), _options.getSourceObjectKey()) +
        "' to '" + getUri(_options.getDestinationBucketName(),
        _options.getDestinationObjectKey()) + "'");
      return Futures.immediateFuture(null);
    }
    else
    {
      ListenableFuture<Copy> copy = startCopy();
      copy = Futures.transform(copy, startPartsAsyncFunction());
      ListenableFuture<String> result = Futures.transform(copy,
        completeAsyncFunction());
      return Futures.transform(
        result,
        new Function<String, S3File>() {
          public S3File apply(String etag) {
            S3File f = new S3File();
            f.setLocalFile(null);
            f.setETag(etag);
            f.setBucketName(_options.getDestinationBucketName());
            f.setKey(_options.getDestinationObjectKey());
            return f;
          }
        }
      );
    }
  }

  /**
   * Step 1: Start copy and fetch metadata.
   */
  private ListenableFuture<Copy> startCopy()
  {
    return executeWithRetry(
      _client.getInternalExecutor(),
      new Callable<ListenableFuture<Copy>>()
      {
        public ListenableFuture<Copy> call()
        {
          return startCopyActual();
        }

        public String toString()
        {
          return "starting copy of " + getUri(
            _options.getSourceBucketName(), _options.getSourceObjectKey()) + " to " + getUri(
            _options.getDestinationBucketName(), _options.getDestinationObjectKey());
        }
      });
  }

  private ListenableFuture<Copy> startCopyActual()
  {
    MultipartAmazonCopyFactory factory = new MultipartAmazonCopyFactory(
      getAmazonS3Client(), _client.getApiExecutor());

    return factory.startCopy(
      _options.getSourceBucketName(), _options.getSourceObjectKey(),
      _options.getDestinationBucketName(), _options.getDestinationObjectKey(),
      _options);
  }

  /**
   * Step 2: Start copying parts
   */
  private AsyncFunction<Copy, Copy> startPartsAsyncFunction()
  {
    return new AsyncFunction<Copy, Copy>()
    {
      public ListenableFuture<Copy> apply(final Copy copy) throws Exception
      {
        return startParts(copy);
      }
    };
  }

  private ListenableFuture<Copy> startParts(Copy copy)
    throws UsageException
  {
    String srcUri = getUri(copy.getSourceBucketName(), copy.getSourceObjectKey());
    String destUri = getUri(copy.getDestinationBucketName(), copy.getDestinationObjectKey());
    
    Map<String,String> meta = copy.getMeta();
    String errPrefix = "Copy of " + srcUri + " to " + destUri + ": ";

    // s3lib-specific metadata should already be set by factory.startCopy
    String objectVersion = meta.get("s3tool-version");

    if (!String.valueOf(Version.CURRENT).equals(objectVersion))
      throw new UsageException(
        errPrefix + "unsupported version: " +  objectVersion +
            ", should be " + Version.CURRENT);

    setFileLength(Long.valueOf(meta.get("s3tool-file-length")));
    setChunkSize(Long.valueOf(meta.get("s3tool-chunk-size")));

    OverallProgressListener opl = null;
    if (_progressListenerFactory != null) {
      opl = _progressListenerFactory.create(
          new ProgressOptionsBuilder()
              .setObjectUri(getUri(copy.getDestinationBucketName(),
                  copy.getDestinationObjectKey()))
              .setOperation("copy")
              .setFileSizeInBytes(fileLength)
              .createProgressOptions());
    }

    List<ListenableFuture<Void>> parts = new ArrayList<>();

    for (long position = 0;
         position < fileLength || (position == 0 && fileLength == 0);
         position += chunkSize)
    {
      parts.add(startPartCopy(copy, position, opl));
    }

    return Futures.transform(Futures.allAsList(parts), Functions.constant(copy));
  }

  private ListenableFuture<Void> startPartCopy(final Copy copy,
                                               final long position,
                                               final OverallProgressListener opl)
  {
    final int partNumber = (int) (position / chunkSize);

    return executeWithRetry(
        _client.getInternalExecutor(),
        new Callable<ListenableFuture<Void>>() {
          public ListenableFuture<Void> call() {
            return startPartCopyActual(copy, position, partNumber, opl);
          }

          public String toString() {
            return "copying part " + (partNumber + 1);
          }
        });
  }

  private ListenableFuture<Void> startPartCopyActual(final Copy copy,
                                                     final long position,
                                                     final int partNumber,
                                                     OverallProgressListener opl)
  {
    // support for testing failures
    String srcUri = getUri(copy.getSourceBucketName(), copy.getSourceObjectKey());
    _options.injectAbort(srcUri);

    Long start;
    Long end;
    long partSize;

    if (copy.getMeta().containsKey("s3tool-key-name"))
    {
      long blockSize;
      try
      {
        blockSize = Cipher.getInstance("AES/CBC/PKCS5Padding").getBlockSize();
      }
      catch (NoSuchAlgorithmException | NoSuchPaddingException e)
      {
        throw new RuntimeException(e);
      }

      long postCryptSize = Math.min(fileLength - position, chunkSize);
      start = partNumber * blockSize * (chunkSize/blockSize + 2);
      partSize = blockSize * (postCryptSize/blockSize + 2);
    }
    else
    {
      start = position;
      partSize = Math.min(fileLength - position, chunkSize);
    }
    end = start + partSize - 1;

    if (fileLength == 0)
    {
      start = null;
      end = null;
    }

    ListenableFuture<Void> copyPartFuture = copy.copyPart(partNumber, start,
        end, opl);

    return copyPartFuture;
  }

  /**
   * Step 3: Complete parts
   */
  private AsyncFunction<Copy, String> completeAsyncFunction()
  {
    return new AsyncFunction<Copy, String>()
    {
      public ListenableFuture<String> apply(Copy copy)
      {
        return complete(copy, 0);
      }
    };
  }

  /**
   * Execute completeActual with retry
   */
  private ListenableFuture<String> complete(final Copy copy,
                                            final int retryCount)
  {
    return executeWithRetry(_client.getInternalExecutor(),
        new Callable<ListenableFuture<String>>()
        {
          public ListenableFuture<String> call()
          {
            return completeActual(copy, retryCount);
          }

          public String toString()
          {
            return "completing copy";
          }
        });
  }

  private ListenableFuture<String> completeActual(final Copy copy,
                                                  final int retryCount)
  {
    return copy.completeCopy();
  }
}
