package org.apache.lucene.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.SegmentInfosReader;
import org.apache.lucene.codecs.SegmentInfosWriter;
import org.apache.lucene.codecs.lucene3x.Lucene3xCodec;
import org.apache.lucene.codecs.lucene3x.Lucene3xSegmentInfosFormat;
import org.apache.lucene.codecs.lucene3x.Lucene3xSegmentInfosReader;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.ChecksumIndexOutput;
import org.apache.lucene.store.DataOutput; // javadocs
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FlushInfo;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NoSuchDirectoryException;
import org.apache.lucene.util.CodecUtil;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.ThreadInterruptedException;

/**
 * A collection of segmentInfo objects with methods for operating on
 * those segments in relation to the file system.
 * <p>
 * The active segments in the index are stored in the segment info file,
 * <tt>segments_N</tt>. There may be one or more <tt>segments_N</tt> files in the
 * index; however, the one with the largest generation is the active one (when
 * older segments_N files are present it's because they temporarily cannot be
 * deleted, or, a writer is in the process of committing, or a custom 
 * {@link org.apache.lucene.index.IndexDeletionPolicy IndexDeletionPolicy}
 * is in use). This file lists each segment by name, has details about the
 * separate norms and deletion files, and also contains the size of each
 * segment.
 * </p>
 * <p>There is also a file <tt>segments.gen</tt>. This file contains
 * the current generation (the <tt>_N</tt> in <tt>segments_N</tt>) of the index.
 * This is used only as a fallback in case the current generation cannot be
 * accurately determined by directory listing alone (as is the case for some NFS
 * clients with time-based directory cache expiration). This file simply contains
 * an {@link DataOutput#writeInt Int32} version header 
 * ({@link #FORMAT_SEGMENTS_GEN_CURRENT}), followed by the
 * generation recorded as {@link DataOutput#writeLong Int64}, written twice.</p>
 * 
 * @lucene.experimental
 */
public final class SegmentInfos implements Cloneable, Iterable<SegmentInfo> {

  /* 
   * The file format version, a negative number.
   *  
   * NOTE: future format numbers must always be one smaller 
   * than the latest. With time, support for old formats will
   * be removed, however the numbers should continue to decrease. 
   */

  // TODO: i don't think we need *all* these version numbers here?
  // most codecs only need FORMAT_CURRENT? and we should rename it 
  // to FORMAT_FLEX? because the 'preamble' is just FORMAT_CURRENT + codecname
  // after that the codec takes over. 
  
  // also i think this class should write this, somehow we let 
  // preflexrw hackishly override this (like seek backwards and overwrite it)

  public static final int VERSION_40 = 0;

  /** Used for the segments.gen file only!
   * Whenever you add a new format, make it 1 smaller (negative version logic)! */
  public static final int FORMAT_SEGMENTS_GEN_CURRENT = -2;

  public int counter;    // used to name new segments
  
  /**
   * counts how often the index has been changed
   */
  public long version;

  private long generation;     // generation of the "segments_N" for the next commit
  private long lastGeneration; // generation of the "segments_N" file we last successfully read
                               // or wrote; this is normally the same as generation except if
                               // there was an IOException that had interrupted a commit

  public Map<String,String> userData = Collections.<String,String>emptyMap();       // Opaque Map<String, String> that user can specify during IndexWriter.commit

  private int format;
  
  private List<SegmentInfo> segments = new ArrayList<SegmentInfo>();
  private Set<SegmentInfo> segmentSet = new HashSet<SegmentInfo>();
  private transient List<SegmentInfo> cachedUnmodifiableList;
  
  private Codec codecFormat;
  
  /**
   * If non-null, information about loading segments_N files
   * will be printed here.  @see #setInfoStream.
   */
  private static PrintStream infoStream = null;
  
  public void setFormat(int format) {
    this.format = format;
  }

  public int getFormat() {
    return format;
  }

  public SegmentInfo info(int i) {
    return segments.get(i);
  }

  /**
   * Get the generation of the most recent commit to the
   * list of index files (N in the segments_N file).
   *
   * @param files -- array of file names to check
   */
  public static long getLastCommitGeneration(String[] files) {
    if (files == null) {
      return -1;
    }
    long max = -1;
    for (String file : files) {
      if (file.startsWith(IndexFileNames.SEGMENTS) && !file.equals(IndexFileNames.SEGMENTS_GEN)) {
        long gen = generationFromSegmentsFileName(file);
        if (gen > max) {
          max = gen;
        }
      }
    }
    return max;
  }

  /**
   * Get the generation of the most recent commit to the
   * index in this directory (N in the segments_N file).
   *
   * @param directory -- directory to search for the latest segments_N file
   */
  public static long getLastCommitGeneration(Directory directory) throws IOException {
    try {
      return getLastCommitGeneration(directory.listAll());
    } catch (NoSuchDirectoryException nsde) {
      return -1;
    }
  }

  /**
   * Get the filename of the segments_N file for the most
   * recent commit in the list of index files.
   *
   * @param files -- array of file names to check
   */

  public static String getLastCommitSegmentsFileName(String[] files) throws IOException {
    return IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                 "",
                                                 getLastCommitGeneration(files));
  }

  /**
   * Get the filename of the segments_N file for the most
   * recent commit to the index in this Directory.
   *
   * @param directory -- directory to search for the latest segments_N file
   */
  public static String getLastCommitSegmentsFileName(Directory directory) throws IOException {
    return IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                 "",
                                                 getLastCommitGeneration(directory));
  }

  /**
   * Get the segments_N filename in use by this segment infos.
   */
  public String getSegmentsFileName() {
    return IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                 "",
                                                 lastGeneration);
  }
  
  /**
   * Parse the generation off the segments file name and
   * return it.
   */
  public static long generationFromSegmentsFileName(String fileName) {
    if (fileName.equals(IndexFileNames.SEGMENTS)) {
      return 0;
    } else if (fileName.startsWith(IndexFileNames.SEGMENTS)) {
      return Long.parseLong(fileName.substring(1+IndexFileNames.SEGMENTS.length()),
                            Character.MAX_RADIX);
    } else {
      throw new IllegalArgumentException("fileName \"" + fileName + "\" is not a segments file");
    }
  }


  /**
   * Get the next segments_N filename that will be written.
   */
  public String getNextSegmentFileName() {
    long nextGeneration;

    if (generation == -1) {
      nextGeneration = 1;
    } else {
      nextGeneration = generation+1;
    }
    return IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                 "",
                                                 nextGeneration);
  }

  /**
   * Read a particular segmentFileName.  Note that this may
   * throw an IOException if a commit is in process.
   *
   * @param directory -- directory containing the segments file
   * @param segmentFileName -- segment file to load
   * @throws CorruptIndexException if the index is corrupt
   * @throws IOException if there is a low-level IO error
   */
  public final void read(Directory directory, String segmentFileName) throws CorruptIndexException, IOException {
    boolean success = false;

    // Clear any previous segments:
    this.clear();

    generation = generationFromSegmentsFileName(segmentFileName);

    lastGeneration = generation;

    // TODO: scary to have default impl reopen the file... but to make it a bit more flexible,
    // maybe we could use a plain indexinput here... could default impl rewind/wrap with checksumII,
    // and any checksumming is then up to implementation?
    ChecksumIndexInput input = new ChecksumIndexInput(directory.openInput(segmentFileName, IOContext.READ));
    try {
      final int format = input.readInt();
      final boolean checkCheckSum;
      if (format == CodecUtil.CODEC_MAGIC) {
        // 4.0+
        CodecUtil.checkHeaderNoMagic(input, "segments", VERSION_40, VERSION_40);
        version = input.readLong();
        counter = input.readInt();
        int numSegments = input.readInt();
        for(int seg=0;seg<numSegments;seg++) {
          String segName = input.readString();
          Codec codec = Codec.forName(input.readString());
          //System.out.println("SIS.read seg=" + seg + " codec=" + codec);
          SegmentInfo info = codec.segmentInfosFormat().getSegmentInfosReader().read(directory, segName, IOContext.READ);
          info.setCodec(codec);
          info.setDelGen(input.readLong());
          info.setDelCount(input.readInt());
          assert info.getDelCount() <= info.docCount;
          add(info);
        }
        userData = input.readStringStringMap();
      } else {
        Lucene3xSegmentInfosReader.readLegacyInfos(this, directory, input, format);
        Codec codec = Codec.forName("Lucene3x");
        for (SegmentInfo info : this) {
          info.setCodec(codec);
        }
      }

      final long checksumNow = input.getChecksum();
      final long checksumThen = input.readLong();
      if (checksumNow != checksumThen) {
        throw new CorruptIndexException("checksum mismatch in segments file (resource: " + input + ")");
      }

      success = true;
    } finally {
      if (!success) {
        // Clear any segment infos we had loaded so we
        // have a clean slate on retry:
        this.clear();
        IOUtils.closeWhileHandlingException(input);
      } else {
        input.close();
      }
    }
  }

  public final void read(Directory directory) throws CorruptIndexException, IOException {
    generation = lastGeneration = -1;

    new FindSegmentsFile(directory) {

      @Override
      protected Object doBody(String segmentFileName) throws CorruptIndexException, IOException {
        read(directory, segmentFileName);
        return null;
      }
    }.run();
  }

  // Only non-null after prepareCommit has been called and
  // before finishCommit is called
  ChecksumIndexOutput pendingSegnOutput;

  private void write(Directory directory) throws IOException {

    String segmentFileName = getNextSegmentFileName();
    
    // Always advance the generation on write:
    if (generation == -1) {
      generation = 1;
    } else {
      generation++;
    }
    
    ChecksumIndexOutput segnOutput = null;
    boolean success = false;

    // nocommit document somewhere taht we store this
    // list-of-segs plus delGen plus other stuff
    // "generically" and then codec gets to write SI

    final Set<String> upgradedSIFiles = new HashSet<String>();

    try {
      segnOutput = new ChecksumIndexOutput(directory.createOutput(segmentFileName, IOContext.DEFAULT));
      CodecUtil.writeHeader(segnOutput, "segments", VERSION_40);
      segnOutput.writeLong(version); 
      segnOutput.writeInt(counter); // write counter
      segnOutput.writeInt(size()); // write infos
      Codec codec3X = Codec.forName("Lucene3x");
      for (SegmentInfo si : this) {
        segnOutput.writeString(si.name);
        segnOutput.writeString(si.getCodec().getName());
        segnOutput.writeLong(si.getDelGen());
        segnOutput.writeInt(si.getDelCount());
        assert si.dir == directory;

        // If this segment is pre-4.x, perform a one-time
        // "ugprade" to write the .si file for it:
        String version = si.getVersion();
        if (version == null || version.startsWith("3.")) {
          String fileName = IndexFileNames.segmentFileName(si.name, "", Lucene3xSegmentInfosFormat.SI_EXTENSION);
          if (!directory.fileExists(fileName)) {
            //System.out.println("write 3x info seg=" + si.name + " version=" + si.getVersion() + " codec=" + si.getCodec().getName());
            upgradedSIFiles.add(write3xInfo(directory, si, IOContext.DEFAULT));
            si.clearFilesCache();
          }
        }
      }
      segnOutput.writeStringStringMap(userData);
      pendingSegnOutput = segnOutput;
      success = true;
    } finally {
      if (!success) {
        // We hit an exception above; try to close the file
        // but suppress any exception:
        IOUtils.closeWhileHandlingException(segnOutput);

        for(String fileName : upgradedSIFiles) {
          try {
            directory.deleteFile(fileName);
          } catch (Throwable t) {
            // Suppress so we keep throwing the original exception
          }
        }

        try {
          // Try not to leave a truncated segments_N file in
          // the index:
          directory.deleteFile(segmentFileName);
        } catch (Throwable t) {
          // Suppress so we keep throwing the original exception
        }
      }
    }
  }

  @Deprecated
  public static String write3xInfo(Directory dir, SegmentInfo si, IOContext context) throws IOException {

    // NOTE: this is NOT how 3.x is really written...
    String fileName = IndexFileNames.segmentFileName(si.name, "", Lucene3xSegmentInfosFormat.SI_EXTENSION);
    //System.out.println("UPGRADE write " + fileName);
    boolean success = false;
    IndexOutput output = dir.createOutput(fileName, context);
    try {
      // we are about to write this SI in 3.x format, dropping all codec information, etc.
      // so it had better be a 3.x segment or you will get very confusing errors later.
      assert si.getCodec() instanceof Lucene3xCodec : "broken test, trying to mix preflex with other codecs";
      assert si.getDelCount() <= si.docCount: "delCount=" + si.getDelCount() + " docCount=" + si.docCount + " segment=" + si.name;
      // Write the Lucene version that created this segment, since 3.1
      output.writeString(si.getVersion());
      output.writeString(si.name);
      output.writeInt(si.docCount);
      output.writeLong(si.getDelGen());

      output.writeInt(si.getDocStoreOffset());
      if (si.getDocStoreOffset() != -1) {
        output.writeString(si.getDocStoreSegment());
        output.writeByte((byte) (si.getDocStoreIsCompoundFile() ? 1:0));
      }
      // pre-4.0 indexes write a byte if there is a single norms file
      output.writeByte((byte) 1);

      Map<Integer,Long> normGen = si.getNormGen();
      if (normGen == null) {
        output.writeInt(SegmentInfo.NO);
      } else {
        output.writeInt(normGen.size());
        for (Map.Entry<Integer,Long> entry : normGen.entrySet()) {
          output.writeLong(entry.getValue());
        }
      }

      output.writeByte((byte) (si.getUseCompoundFile() ? SegmentInfo.YES : SegmentInfo.NO));
      output.writeInt(si.getDelCount());
      // hasProx:
      output.writeByte((byte) 1);
      output.writeStringStringMap(si.getDiagnostics());
      // hasVectors:
      output.writeByte((byte) 1);

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(output);
        try {
          si.dir.deleteFile(fileName);
        } catch (Throwable t) {
          // Suppress so we keep throwing the original exception
        }
      } else {
        output.close();
      }
    }

    return fileName;
  }

  /**
   * Returns a copy of this instance, also copying each
   * SegmentInfo.
   */
  
  @Override
  public SegmentInfos clone() {
    try {
      final SegmentInfos sis = (SegmentInfos) super.clone();
      // deep clone, first recreate all collections:
      sis.segments = new ArrayList<SegmentInfo>(size());
      sis.segmentSet = new HashSet<SegmentInfo>(size());
      sis.cachedUnmodifiableList = null;
      for(final SegmentInfo info : this) {
        assert info.getCodec() != null;
        // dont directly access segments, use add method!!!
        sis.add(info.clone());
      }
      sis.userData = new HashMap<String,String>(userData);
      return sis;
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException("should not happen", e);
    }
  }

  /**
   * version number when this SegmentInfos was generated.
   */
  public long getVersion() {
    return version;
  }
  public long getGeneration() {
    return generation;
  }
  public long getLastGeneration() {
    return lastGeneration;
  }

  /** If non-null, information about retries when loading
   * the segments file will be printed to this.
   */
  public static void setInfoStream(PrintStream infoStream) {
    SegmentInfos.infoStream = infoStream;
  }

  /* Advanced configuration of retry logic in loading
     segments_N file */
  private static int defaultGenLookaheadCount = 10;

  /**
   * Advanced: set how many times to try incrementing the
   * gen when loading the segments file.  This only runs if
   * the primary (listing directory) and secondary (opening
   * segments.gen file) methods fail to find the segments
   * file.
   *
   * @lucene.experimental
   */
  public static void setDefaultGenLookaheadCount(int count) {
    defaultGenLookaheadCount = count;
  }
  /**
   * @see #setDefaultGenLookaheadCount
   *
   * @lucene.experimental
   */
  public static int getDefaultGenLookahedCount() {
    return defaultGenLookaheadCount;
  }

  /**
   * @see #setInfoStream
   */
  public static PrintStream getInfoStream() {
    return infoStream;
  }

  /**
   * Prints the given message to the infoStream. Note, this method does not
   * check for null infoStream. It assumes this check has been performed by the
   * caller, which is recommended to avoid the (usually) expensive message
   * creation.
   */
  private static void message(String message) {
    infoStream.println("SIS [" + Thread.currentThread().getName() + "]: " + message);
  }

  /**
   * Utility class for executing code that needs to do
   * something with the current segments file.  This is
   * necessary with lock-less commits because from the time
   * you locate the current segments file name, until you
   * actually open it, read its contents, or check modified
   * time, etc., it could have been deleted due to a writer
   * commit finishing.
   */
  public abstract static class FindSegmentsFile {
    
    final Directory directory;

    public FindSegmentsFile(Directory directory) {
      this.directory = directory;
    }

    public Object run() throws CorruptIndexException, IOException {
      return run(null);
    }
    
    public Object run(IndexCommit commit) throws CorruptIndexException, IOException {
      if (commit != null) {
        if (directory != commit.getDirectory())
          throw new IOException("the specified commit does not match the specified Directory");
        return doBody(commit.getSegmentsFileName());
      }

      String segmentFileName = null;
      long lastGen = -1;
      long gen = 0;
      int genLookaheadCount = 0;
      IOException exc = null;
      int retryCount = 0;

      boolean useFirstMethod = true;

      // Loop until we succeed in calling doBody() without
      // hitting an IOException.  An IOException most likely
      // means a commit was in process and has finished, in
      // the time it took us to load the now-old infos files
      // (and segments files).  It's also possible it's a
      // true error (corrupt index).  To distinguish these,
      // on each retry we must see "forward progress" on
      // which generation we are trying to load.  If we
      // don't, then the original error is real and we throw
      // it.
      
      // We have three methods for determining the current
      // generation.  We try the first two in parallel (when
      // useFirstMethod is true), and fall back to the third
      // when necessary.

      while(true) {

        if (useFirstMethod) {

          // List the directory and use the highest
          // segments_N file.  This method works well as long
          // as there is no stale caching on the directory
          // contents (NOTE: NFS clients often have such stale
          // caching):
          String[] files = null;

          long genA = -1;

          files = directory.listAll();
          
          if (files != null) {
            genA = getLastCommitGeneration(files);
          }
          
          if (infoStream != null) {
            message("directory listing genA=" + genA);
          }

          // Also open segments.gen and read its
          // contents.  Then we take the larger of the two
          // gens.  This way, if either approach is hitting
          // a stale cache (NFS) we have a better chance of
          // getting the right generation.
          long genB = -1;
          IndexInput genInput = null;
          try {
            genInput = directory.openInput(IndexFileNames.SEGMENTS_GEN, IOContext.READONCE);
          } catch (FileNotFoundException e) {
            if (infoStream != null) {
              message("segments.gen open: FileNotFoundException " + e);
            }
          } catch (IOException e) {
            if (infoStream != null) {
              message("segments.gen open: IOException " + e);
            }
          }
  
          if (genInput != null) {
            try {
              int version = genInput.readInt();
              if (version == FORMAT_SEGMENTS_GEN_CURRENT) {
                long gen0 = genInput.readLong();
                long gen1 = genInput.readLong();
                if (infoStream != null) {
                  message("fallback check: " + gen0 + "; " + gen1);
                }
                if (gen0 == gen1) {
                  // The file is consistent.
                  genB = gen0;
                }
              } else {
                throw new IndexFormatTooNewException(genInput, version, FORMAT_SEGMENTS_GEN_CURRENT, FORMAT_SEGMENTS_GEN_CURRENT);
              }
            } catch (IOException err2) {
              // rethrow any format exception
              if (err2 instanceof CorruptIndexException) throw err2;
            } finally {
              genInput.close();
            }
          }

          if (infoStream != null) {
            message(IndexFileNames.SEGMENTS_GEN + " check: genB=" + genB);
          }

          // Pick the larger of the two gen's:
          gen = Math.max(genA, genB);

          if (gen == -1) {
            // Neither approach found a generation
            throw new IndexNotFoundException("no segments* file found in " + directory + ": files: " + Arrays.toString(files));
          }
        }

        if (useFirstMethod && lastGen == gen && retryCount >= 2) {
          // Give up on first method -- this is 3rd cycle on
          // listing directory and checking gen file to
          // attempt to locate the segments file.
          useFirstMethod = false;
        }

        // Second method: since both directory cache and
        // file contents cache seem to be stale, just
        // advance the generation.
        if (!useFirstMethod) {
          if (genLookaheadCount < defaultGenLookaheadCount) {
            gen++;
            genLookaheadCount++;
            if (infoStream != null) {
              message("look ahead increment gen to " + gen);
            }
          } else {
            // All attempts have failed -- throw first exc:
            throw exc;
          }
        } else if (lastGen == gen) {
          // This means we're about to try the same
          // segments_N last tried.
          retryCount++;
        } else {
          // Segment file has advanced since our last loop
          // (we made "progress"), so reset retryCount:
          retryCount = 0;
        }

        lastGen = gen;

        segmentFileName = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                                "",
                                                                gen);

        try {
          Object v = doBody(segmentFileName);
          if (infoStream != null) {
            message("success on " + segmentFileName);
          }
          return v;
        } catch (IOException err) {

          // Save the original root cause:
          if (exc == null) {
            exc = err;
          }

          if (infoStream != null) {
            message("primary Exception on '" + segmentFileName + "': " + err + "'; will retry: retryCount=" + retryCount + "; gen = " + gen);
          }

          if (gen > 1 && useFirstMethod && retryCount == 1) {

            // This is our second time trying this same segments
            // file (because retryCount is 1), and, there is
            // possibly a segments_(N-1) (because gen > 1).
            // So, check if the segments_(N-1) exists and
            // try it if so:
            String prevSegmentFileName = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                                               "",
                                                                               gen-1);

            final boolean prevExists;
            prevExists = directory.fileExists(prevSegmentFileName);

            if (prevExists) {
              if (infoStream != null) {
                message("fallback to prior segment file '" + prevSegmentFileName + "'");
              }
              try {
                Object v = doBody(prevSegmentFileName);
                if (infoStream != null) {
                  message("success on fallback " + prevSegmentFileName);
                }
                return v;
              } catch (IOException err2) {
                if (infoStream != null) {
                  message("secondary Exception on '" + prevSegmentFileName + "': " + err2 + "'; will retry");
                }
              }
            }
          }
        }
      }
    }

    /**
     * Subclass must implement this.  The assumption is an
     * IOException will be thrown if something goes wrong
     * during the processing that could have been caused by
     * a writer committing.
     */
    protected abstract Object doBody(String segmentFileName) throws CorruptIndexException, IOException;
  }

  // Carry over generation numbers from another SegmentInfos
  void updateGeneration(SegmentInfos other) {
    lastGeneration = other.lastGeneration;
    generation = other.generation;
  }

  final void rollbackCommit(Directory dir) throws IOException {
    if (pendingSegnOutput != null) {
      try {
        pendingSegnOutput.close();
      } catch (Throwable t) {
        // Suppress so we keep throwing the original exception
        // in our caller
      }

      // Must carefully compute fileName from "generation"
      // since lastGeneration isn't incremented:
      try {
        final String segmentFileName = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS,
                                                                              "",
                                                                             generation);
        dir.deleteFile(segmentFileName);
      } catch (Throwable t) {
        // Suppress so we keep throwing the original exception
        // in our caller
      }
      pendingSegnOutput = null;
    }
  }

  /** Call this to start a commit.  This writes the new
   *  segments file, but writes an invalid checksum at the
   *  end, so that it is not visible to readers.  Once this
   *  is called you must call {@link #finishCommit} to complete
   *  the commit or {@link #rollbackCommit} to abort it.
   *  <p>
   *  Note: {@link #changed()} should be called prior to this
   *  method if changes have been made to this {@link SegmentInfos} instance
   *  </p>  
   **/
  final void prepareCommit(Directory dir) throws IOException {
    if (pendingSegnOutput != null) {
      throw new IllegalStateException("prepareCommit was already called");
    }
    write(dir);
  }

  /** Returns all file names referenced by SegmentInfo
   *  instances matching the provided Directory (ie files
   *  associated with any "external" segments are skipped).
   *  The returned collection is recomputed on each
   *  invocation.  */
  public Collection<String> files(Directory dir, boolean includeSegmentsFile) throws IOException {
    HashSet<String> files = new HashSet<String>();
    if (includeSegmentsFile) {
      final String segmentFileName = getSegmentsFileName();
      if (segmentFileName != null) {
        /*
         * TODO: if lastGen == -1 we get might get null here it seems wrong to
         * add null to the files set
         */
        files.add(segmentFileName);
      }
    }
    final int size = size();
    for(int i=0;i<size;i++) {
      final SegmentInfo info = info(i);
      if (info.dir == dir) {
        files.addAll(info(i).files());
      }
    }
    return files;
  }

  final void finishCommit(Directory dir) throws IOException {
    if (pendingSegnOutput == null) {
      throw new IllegalStateException("prepareCommit was not called");
    }
    boolean success = false;
    try {
      pendingSegnOutput.finishCommit();
      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(pendingSegnOutput);
        rollbackCommit(dir);
      } else {
        pendingSegnOutput.close();
        pendingSegnOutput = null;
      }
    }

    // NOTE: if we crash here, we have left a segments_N
    // file in the directory in a possibly corrupt state (if
    // some bytes made it to stable storage and others
    // didn't).  But, the segments_N file includes checksum
    // at the end, which should catch this case.  So when a
    // reader tries to read it, it will throw a
    // CorruptIndexException, which should cause the retry
    // logic in SegmentInfos to kick in and load the last
    // good (previous) segments_N-1 file.

    final String fileName = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", generation);
    success = false;
    try {
      dir.sync(Collections.singleton(fileName));
      success = true;
    } finally {
      if (!success) {
        try {
          dir.deleteFile(fileName);
        } catch (Throwable t) {
          // Suppress so we keep throwing the original exception
        }
      }
    }

    lastGeneration = generation;

    try {
      IndexOutput genOutput = dir.createOutput(IndexFileNames.SEGMENTS_GEN, IOContext.READONCE);
      try {
        genOutput.writeInt(FORMAT_SEGMENTS_GEN_CURRENT);
        genOutput.writeLong(generation);
        genOutput.writeLong(generation);
      } finally {
        genOutput.close();
        dir.sync(Collections.singleton(IndexFileNames.SEGMENTS_GEN));
      }
    } catch (Throwable t) {
      // It's OK if we fail to write this file since it's
      // used only as one of the retry fallbacks.
      try {
        dir.deleteFile(IndexFileNames.SEGMENTS_GEN);
      } catch (Throwable t2) {
        // Ignore; this file is only used in a retry
        // fallback on init.
      }
      if (t instanceof ThreadInterruptedException) {
        throw (ThreadInterruptedException) t;
      }
    }
  }

  /** Writes & syncs to the Directory dir, taking care to
   *  remove the segments file on exception
   *  <p>
   *  Note: {@link #changed()} should be called prior to this
   *  method if changes have been made to this {@link SegmentInfos} instance
   *  </p>  
   **/
  final void commit(Directory dir) throws IOException {
    prepareCommit(dir);
    finishCommit(dir);
  }

  public String toString(Directory directory) {
    StringBuilder buffer = new StringBuilder();
    buffer.append(getSegmentsFileName()).append(": ");
    final int count = size();
    for(int i = 0; i < count; i++) {
      if (i > 0) {
        buffer.append(' ');
      }
      final SegmentInfo info = info(i);
      buffer.append(info.toString(directory, 0));
    }
    return buffer.toString();
  }

  public Map<String,String> getUserData() {
    return userData;
  }

  void setUserData(Map<String,String> data) {
    if (data == null) {
      userData = Collections.<String,String>emptyMap();
    } else {
      userData = data;
    }
  }

  /** Replaces all segments in this instance, but keeps
   *  generation, version, counter so that future commits
   *  remain write once.
   */
  void replace(SegmentInfos other) {
    rollbackSegmentInfos(other.asList());
    lastGeneration = other.lastGeneration;
    format = other.format;
  }

  /** Returns sum of all segment's docCounts.  Note that
   *  this does not include deletions */
  public int totalDocCount() {
    int count = 0;
    for(SegmentInfo info : this) {
      count += info.docCount;
    }
    return count;
  }

  /** Call this before committing if changes have been made to the
   *  segments. */
  public void changed() {
    version++;
  }
  
  /** applies all changes caused by committing a merge to this SegmentInfos */
  void applyMergeChanges(MergePolicy.OneMerge merge, boolean dropSegment) {
    final Set<SegmentInfo> mergedAway = new HashSet<SegmentInfo>(merge.segments);
    boolean inserted = false;
    int newSegIdx = 0;
    for (int segIdx = 0, cnt = segments.size(); segIdx < cnt; segIdx++) {
      assert segIdx >= newSegIdx;
      final SegmentInfo info = segments.get(segIdx);
      if (mergedAway.contains(info)) {
        if (!inserted && !dropSegment) {
          segments.set(segIdx, merge.info);
          inserted = true;
          newSegIdx++;
        }
      } else {
        segments.set(newSegIdx, info);
        newSegIdx++;
      }
    }

    // the rest of the segments in list are duplicates, so don't remove from map, only list!
    segments.subList(newSegIdx, segments.size()).clear();
    
    // Either we found place to insert segment, or, we did
    // not, but only because all segments we merged becamee
    // deleted while we are merging, in which case it should
    // be the case that the new segment is also all deleted,
    // we insert it at the beginning if it should not be dropped:
    if (!inserted && !dropSegment) {
      segments.add(0, merge.info);
    }

    // update the Set
    if (!dropSegment) {
      segmentSet.add(merge.info);
    }
    segmentSet.removeAll(mergedAway);
    
    assert segmentSet.size() == segments.size();
  }

  List<SegmentInfo> createBackupSegmentInfos(boolean cloneChildren) {
    if (cloneChildren) {
      final List<SegmentInfo> list = new ArrayList<SegmentInfo>(size());
      for(final SegmentInfo info : this) {
        assert info.getCodec() != null;
        list.add(info.clone());
      }
      return list;
    } else {
      return new ArrayList<SegmentInfo>(segments);
    }
  }
  
  void rollbackSegmentInfos(List<SegmentInfo> infos) {
    this.clear();
    this.addAll(infos);
  }
  
  /**
   * Returns the codec used to decode this SegmentInfos from disk 
   * @lucene.internal
   */
  Codec codecFormat() {
    return codecFormat;
  }
  
  /** Returns an <b>unmodifiable</b> {@link Iterator} of contained segments in order. */
  // @Override (comment out until Java 6)
  public Iterator<SegmentInfo> iterator() {
    return asList().iterator();
  }
  
  /** Returns all contained segments as an <b>unmodifiable</b> {@link List} view. */
  public List<SegmentInfo> asList() {
    if (cachedUnmodifiableList == null) {
      cachedUnmodifiableList = Collections.unmodifiableList(segments);
    }
    return cachedUnmodifiableList;
  }
  
  public int size() {
    return segments.size();
  }

  public void add(SegmentInfo si) {
    if (segmentSet.contains(si)) {
      throw new IllegalStateException("Cannot add the same segment two times to this SegmentInfos instance");
    }
    segments.add(si);
    segmentSet.add(si);
    assert segmentSet.size() == segments.size();
  }
  
  public void addAll(Iterable<SegmentInfo> sis) {
    for (final SegmentInfo si : sis) {
      this.add(si);
    }
  }
  
  public void clear() {
    segments.clear();
    segmentSet.clear();
  }
  
  public void remove(SegmentInfo si) {
    final int index = this.indexOf(si);
    if (index >= 0) {
      this.remove(index);
    }
  }
  
  public void remove(int index) {
    segmentSet.remove(segments.remove(index));
    assert segmentSet.size() == segments.size();
  }
  
  public boolean contains(SegmentInfo si) {
    return segmentSet.contains(si);
  }

  public int indexOf(SegmentInfo si) {
    if (segmentSet.contains(si)) {
      return segments.indexOf(si);
    } else {
      return -1;
    }
  }
}
