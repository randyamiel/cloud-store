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

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.FutureFallback;

public class DownloadCommand extends Command
{
  private ListeningExecutorService _downloadExecutor;
  private ListeningScheduledExecutorService _executor;
  private KeyProvider _encKeyProvider;

  public DownloadCommand(
    ListeningExecutorService downloadExecutor,
    ListeningScheduledExecutorService internalExecutor,
    File file,
    KeyProvider encKeyProvider)
  throws IOException
  {
    _downloadExecutor = downloadExecutor;
    _executor = internalExecutor;
    _encKeyProvider = encKeyProvider;

    this.file = file;
    createNewFile();
  }

  private void createNewFile() throws IOException
  {
    file = file.getAbsoluteFile();
    File dir = file.getParentFile();
    if(!dir.exists())
    {
      if(!dir.mkdirs())
        throw new IOException("Could not create directory '" + dir + "'");
    }

    if(file.exists() && !file.delete())
      throw new IOException("Could not delete existing file '" + file + "'");

    if(!file.createNewFile())
      throw new IOException("File '" + file + "' already exists");
  }

  public ListenableFuture<S3File> run(final String bucket, final String key)
  {
    ListenableFuture<AmazonDownload> download = startDownload(bucket, key);
    download = Futures.transform(download, startPartsAsyncFunction());
    ListenableFuture<S3File> res = Futures.transform(
      download,
      new Function<AmazonDownload, S3File>()
      {
        public S3File apply(AmazonDownload download)
        {
          S3File f = new S3File();
          f.setLocalFile(DownloadCommand.this.file);
          f.setETag(download.getETag());
          f.setBucketName(bucket);
          f.setKey(key);
          return f;
        }
      }
    );

    return Futures.withFallback(
      res,
      new FutureFallback<S3File>()
      {
        public ListenableFuture<S3File> create(Throwable t)
        {
          return Futures.immediateFailedFuture(new Exception("Error downloading s3://"+bucket+"/"+key+".", t));
        }
      });
  }

  /**
   * Step 1: Start download and fetch metadata.
   */
  private ListenableFuture<AmazonDownload> startDownload(final String bucket, final String key)
  {
    return executeWithRetry(
      _executor,
      new Callable<ListenableFuture<AmazonDownload>>()
      {
        public ListenableFuture<AmazonDownload> call()
        {
          return startDownloadActual(bucket, key);
        }

        public String toString()
        {
          return "starting download " + bucket + "/" + key;
        }
      });
  }

  private ListenableFuture<AmazonDownload> startDownloadActual(final String bucket, final String key)
  {
    AmazonDownloadFactory factory = new AmazonDownloadFactory(getAmazonS3Client(), _downloadExecutor);
    return factory.startDownload(bucket, key);
  }

  /**
   * Step 2: Start downloading parts
   */
  private AsyncFunction<AmazonDownload, AmazonDownload> startPartsAsyncFunction()
  {
    return new AsyncFunction<AmazonDownload, AmazonDownload>()
    {
      public ListenableFuture<AmazonDownload> apply(AmazonDownload download) throws Exception
      {
        return startParts(download);
      }
    };
  }

  private ListenableFuture<AmazonDownload> startParts(AmazonDownload download)
  throws IOException, UsageException
  {
    Map<String,String> meta = download.getMeta();

    String errPrefix = "s3://"+download.getBucket()+"/"+download.getKey()+": ";
    if (meta.containsKey("s3tool-version"))
    {
      String objectVersion = meta.get("s3tool-version");

      if (!String.valueOf(Version.CURRENT).equals(objectVersion))
        throw new UsageException(
          errPrefix+"file uploaded with unsupported version: " + objectVersion + ", should be " + Version.CURRENT);

      if (meta.containsKey("s3tool-key-name"))
      {
        String keyName = meta.get("s3tool-key-name");
        Key privKey;
        try
        {
          privKey = _encKeyProvider.getPrivateKey(keyName);
        }
        catch (NoSuchKeyException e)
        {
          throw new UsageException(errPrefix + "private key '" + keyName + "' is not available to decrypt");
        }

        Cipher cipher;
        byte[] encKeyBytes;
        try
        {
          cipher = Cipher.getInstance("RSA");
          cipher.init(Cipher.DECRYPT_MODE, privKey);
          encKeyBytes = cipher.doFinal(DatatypeConverter.parseBase64Binary(meta.get("s3tool-symmetric-key")));
        }
        catch (NoSuchAlgorithmException e)
        {
          throw new RuntimeException(e);
        }
        catch (NoSuchPaddingException e)
        {
          throw new RuntimeException(e);
        }
        catch (InvalidKeyException e)
        {
          throw new RuntimeException(e);
        }
        catch (IllegalBlockSizeException e)
        {
          throw new RuntimeException(e);
        }
        catch (BadPaddingException e)
        {
          throw new RuntimeException(e);
        }

        encKey = new SecretKeySpec(encKeyBytes, "AES");
      }

      setChunkSize(Long.valueOf(meta.get("s3tool-chunk-size")));
      fileLength = Long.valueOf(meta.get("s3tool-file-length"));
    }
    else
    {
      fileLength = download.getLength();
      if (chunkSize == 0)
      {
        setChunkSize(Math.min(fileLength, Utils.getDefaultChunkSize()));
      }
    }

    List<ListenableFuture<Integer>> parts = new ArrayList<ListenableFuture<Integer>>();
    for (long position = 0; position < fileLength; position += chunkSize)
    {
      parts.add(startPartDownload(download, position));
    }

    return Futures.transform(Futures.allAsList(parts), Functions.constant(download));
  }

  private ListenableFuture<Integer> startPartDownload(final AmazonDownload download, final long position)
  {
    final int partNumber = (int) (position / chunkSize);

    return executeWithRetry(
      _executor,
      new Callable<ListenableFuture<Integer>>()
      {
        public ListenableFuture<Integer> call()
        {
          return startPartDownloadActual(download, position);
        }

        public String toString()
        {
          return "downloading part " + partNumber;
        }
      });
  }

  private ListenableFuture<Integer> startPartDownloadActual(final AmazonDownload download, final long position)
  {
    final int partNumber = (int) (position / chunkSize);
    long start;
    long partSize;

    if (encKey != null)
    {
      long blockSize;
      try
      {
        blockSize = Cipher.getInstance("AES/CBC/PKCS5Padding").getBlockSize();
      }
      catch (NoSuchAlgorithmException e)
      {
        throw new RuntimeException(e);
      }
      catch (NoSuchPaddingException e)
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

    ListenableFuture<InputStream> getPartFuture = download.getPart(start, start + partSize - 1);

    AsyncFunction<InputStream, Integer> readDownloadFunction = new AsyncFunction<InputStream, Integer>()
    {
      public ListenableFuture<Integer> apply(InputStream stream) throws Exception
      {
        try
        {
          readDownload(download, stream, position, partNumber);
          return Futures.immediateFuture(partNumber);
        } finally {
          // make sure that the stream is always closed
          try
          {
            stream.close();
          }
          catch (IOException e) {}
        }
      }
    };

    return Futures.transform(getPartFuture, readDownloadFunction);
  }

  private void readDownload(AmazonDownload download, InputStream stream, long position, int partNumber)
  throws Exception
  {
    RandomAccessFile out = new RandomAccessFile(file, "rw");
    out.seek(position);

    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

    InputStream in;
    if (encKey != null)
    {
      in = new CipherWithInlineIVInputStream(stream, cipher, Cipher.DECRYPT_MODE, encKey);
    }
    else
    {
      in = stream;
    }

    int postCryptSize = (int) Math.min(fileLength - position, chunkSize);
    int bufSize = 8192;
    int offset = 0;
    byte[] buf = new byte[bufSize];
    while (offset < postCryptSize)
    {
      int result;

      try
      {
        result = in.read(buf, 0, Math.min(bufSize, postCryptSize - offset));
      }
      catch (IOException e)
      {
        try
        {
          out.close();
          stream.close();
        }
        catch (IOException ignored) {}
        throw e;
      }

      if (result == -1)
      {
        try
        {
          out.close();
          stream.close();
        }
        catch (IOException e) {}

        throw new IOException("unexpected EOF");
      }

      try
      {
        out.write(buf, 0, result);
      }
      catch (IOException e)
      {
        try
        {
          out.close();
          stream.close();
        }
        catch (IOException ignored) {}
        throw e;
      }

      offset += result;
    }

    try
    {
      out.close();
      stream.close();
    }
    catch (IOException e) {}
  }
}
