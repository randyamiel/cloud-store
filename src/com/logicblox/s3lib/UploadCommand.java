package com.logicblox.s3lib;

import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.BufferedInputStream;
import java.io.RandomAccessFile;
import java.io.ObjectInputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import java.security.SecureRandom;
import java.security.Key;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidAlgorithmParameterException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

public class UploadCommand extends Command
{
  private String encKeyName;
  private String encryptedSymmetricKeyString;
  private CannedAccessControlList acl;

  private ListeningExecutorService _uploadExecutor;
  private ListeningScheduledExecutorService _executor;

  public UploadCommand(
    ListeningExecutorService uploadExecutor,
    ListeningScheduledExecutorService internalExecutor,
    File file,
    long chunkSize,
    String encKeyName,
    KeyProvider encKeyProvider,
    CannedAccessControlList acl)
  throws IOException
  {
    if(uploadExecutor == null)
      throw new IllegalArgumentException("non-null upload executor is required");
    if(internalExecutor == null)
      throw new IllegalArgumentException("non-null internal executor is required");

    _uploadExecutor = uploadExecutor;
    _executor = internalExecutor;

    this.file = file;
    setChunkSize(chunkSize);
    this.fileLength = file.length();
    this.encKeyName = encKeyName;

    if (this.encKeyName != null) {
      byte[] encKeyBytes = new byte[32];
      new SecureRandom().nextBytes(encKeyBytes);
      this.encKey = new SecretKeySpec(encKeyBytes, "AES");
      try
      {
        Key pubKey = encKeyProvider.getPublicKey(this.encKeyName);
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        this.encryptedSymmetricKeyString = DatatypeConverter.printBase64Binary(cipher.doFinal(encKeyBytes));
      } catch (NoSuchKeyException e) {
        throw new UsageException("Missing encryption key: " + this.encKeyName);
      } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
      } catch (NoSuchPaddingException e) {
        throw new RuntimeException(e);
      } catch (InvalidKeyException e) {
        throw new RuntimeException(e);
      } catch (IllegalBlockSizeException e) {
        throw new RuntimeException(e);
      } catch (BadPaddingException e) {
        throw new RuntimeException(e);
      }
    }

    this.acl = acl;
  }

  /**
   * Run ties Step 1, Step 2, and Step 3 together. The return result is the ETag of the upload.
   */
  public ListenableFuture<S3File> run(final String bucket, final String key) throws FileNotFoundException
  {
    if (!file.exists())
      throw new FileNotFoundException(file.getPath());

    ListenableFuture<Upload> upload = startUpload(bucket, key);
    upload = Futures.transform(upload, startPartsAsyncFunction());
    ListenableFuture<String> result = Futures.transform(upload, completeAsyncFunction());
    return Futures.transform(result,
      new Function<String, S3File>()
      {
        public S3File apply(String etag)
        {
          S3File f = new S3File();
          f.setLocalFile(file);
          f.setETag(etag);
          f.setBucketName(bucket);
          f.setKey(key);
          return f;
        }
      });
  }

  /**
   * Step 1: Returns a future upload that is internally retried.
   */
  private ListenableFuture<Upload> startUpload(final String bucket, final String key)
  {
    return executeWithRetry(_executor,
      new Callable<ListenableFuture<Upload>>()
      {
        public ListenableFuture<Upload> call()
        {
          return startUploadActual(bucket, key);
        }

        public String toString()
        {
          return "starting upload " + bucket + "/" + key;
        }
      });
  }

  private ListenableFuture<Upload> startUploadActual(final String bucket, final String key)
  {
    UploadFactory factory = new MultipartAmazonUploadFactory(getAmazonS3Client(), _uploadExecutor);

    Map<String,String> meta = new HashMap<String,String>();
    meta.put("s3tool-version", String.valueOf(Version.CURRENT));
    if (this.encKeyName != null) {
      meta.put("s3tool-key-name", encKeyName);
      meta.put("s3tool-symmetric-key", encryptedSymmetricKeyString);
    }
    meta.put("s3tool-chunk-size", Long.toString(chunkSize));
    meta.put("s3tool-file-length", Long.toString(fileLength));

    return factory.startUpload(bucket, key, meta, acl);
  }

  /**
   * Step 2: Upload parts
   */
  private AsyncFunction<Upload, Upload> startPartsAsyncFunction()
  {
    return new AsyncFunction<Upload, Upload>()
    {
      public ListenableFuture<Upload> apply(Upload upload)
      {
        return startParts(upload);
      }
    };
  }

  private ListenableFuture<Upload> startParts(final Upload upload)
  {
    List<ListenableFuture<Void>> parts = new ArrayList<ListenableFuture<Void>>();

    for (long position = 0; position < fileLength; position += chunkSize)
    {
      parts.add(startPartUploadThread(upload, position));
    }

    // we do not care about the voids, so we just return the upload
    // object.
    return Futures.transform(
      Futures.allAsList(parts),
      Functions.constant(upload));
  }

  private ListenableFuture<Void> startPartUploadThread(final Upload upload, final long position)
  {
    ListenableFuture<ListenableFuture<Void>> result =
      _executor.submit(new Callable<ListenableFuture<Void>>()
        {
          public ListenableFuture<Void> call() throws Exception
          {
            return UploadCommand.this.startPartUpload(upload, position);
          }
        });

    return Futures.dereference(result);
  }

  /**
   * Execute startPartUpload with retry
   */
  private ListenableFuture<Void> startPartUpload(final Upload upload, final long position)
  {
    final int partNumber = (int) (position / chunkSize);

    return executeWithRetry(_executor,
      new Callable<ListenableFuture<Void>>()
      {
        public ListenableFuture<Void> call() throws Exception
        {
          return startPartUploadActual(upload, position);
        }

        public String toString()
        {
          return "uploading part " + partNumber;
        }
      });
  }

  private ListenableFuture<Void> startPartUploadActual(final Upload upload, final long position)
  throws Exception
  {
    final int partNumber = (int) (position / chunkSize);
    final FileInputStream fs = new FileInputStream(file);

    long skipped = fs.skip(position);
    while (skipped < position)
    {
      skipped += fs.skip(position - skipped);
    }

    BufferedInputStream bs = new BufferedInputStream(fs);
    InputStream in;
    long partSize;
    if (this.encKeyName != null)
    {
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      in = new CipherWithInlineIVInputStream(bs, cipher, Cipher.ENCRYPT_MODE, encKey);

      long preCryptSize = Math.min(fileLength - position, chunkSize);
      long blockSize = cipher.getBlockSize();
      partSize = blockSize * (preCryptSize/blockSize + 2);
    }
    else
    {
      in = bs;
      partSize = Math.min(fileLength - position, chunkSize);
    }

    ListenableFuture<Void> uploadPartFuture = upload.uploadPart(partNumber, in, partSize);

    FutureFallback<Void> closeFile = new FutureFallback<Void>()
      {
        public ListenableFuture<Void> create(Throwable thrown) throws Exception
        {
          try {
            fs.close();
          } catch (Exception e) {
          }

            return Futures.immediateFailedFuture(thrown);
        }
      };

    Futures.addCallback(uploadPartFuture, new FutureCallback<Void>()
      {
        public void onFailure(Throwable t) {}
        public void onSuccess(Void ignored) {
          try {
            fs.close();
          } catch (Exception e) {
          }
        }
      });

    return Futures.withFallback(uploadPartFuture, closeFile);
  }

  /**
   * Step 3: Complete parts
   */
  private AsyncFunction<Upload, String> completeAsyncFunction()
  {
    return new AsyncFunction<Upload, String>()
    {
      public ListenableFuture<String> apply(Upload upload)
      {
        return complete(upload, 0);
      }
    };
  }

  /**
   * Execute completeActual with retry
   */
  private ListenableFuture<String> complete(final Upload upload, final int retryCount)
  {
    return executeWithRetry(_executor,
      new Callable<ListenableFuture<String>>()
      {
        public ListenableFuture<String> call()
        {
          return completeActual(upload, retryCount);
        }

        public String toString()
        {
          return "completing upload";
        }
      });
  }

  private ListenableFuture<String> completeActual(final Upload upload, final int retryCount)
  {
    return upload.completeUpload();
  }
}
