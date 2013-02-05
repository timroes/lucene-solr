package org.apache.lucene.util.android;

import java.util.LinkedList;
import java.util.List;
import java.util.ServiceConfigurationError;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;

/*
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

/**
 * Loads services from a static list instead of META-INF, that doesn't exist
 * in Android applications.
 * 
 * @author Tim Roes <tim.roes@inovex.de>
 */
public class AndroidServiceLoader {
  
  /**
   * Hard coded list of codecs from all META-INF/services/ files.
   */
  private static final String[] CODEC_NAMES = new String[]{
    "org.apache.lucene.codecs.lucene40.Lucene40Codec",
    "org.apache.lucene.codecs.lucene3x.Lucene3xCodec",
    "org.apache.lucene.codecs.lucene41.Lucene41Codec",
    "org.apache.lucene.codecs.simpletext.SimpleTextCodec",
    "org.apache.lucene.codecs.appending.AppendingCodec"
  };
  
  /**
   * Hard coded list of posting formats from all META-INF/services/ files.
   */
  private static final String[] POSTING_FORMAT_NAMES = new String[]{
    "org.apache.lucene.codecs.lucene40.Lucene40PostingsFormat",
    "org.apache.lucene.codecs.lucene41.Lucene41PostingsFormat",
    "org.apache.lucene.codecs.pulsing.Pulsing41PostingsFormat",
    "org.apache.lucene.codecs.simpletext.SimpleTextPostingsFormat",
    "org.apache.lucene.codecs.memory.MemoryPostingsFormat",
    "org.apache.lucene.codecs.bloom.BloomFilteringPostingsFormat",
    "org.apache.lucene.codecs.memory.DirectPostingsFormat"
  };
  
  @SuppressWarnings("unchecked")
  private static <T> Iterable<Class<? extends T>> getClasses(Class<T> clazz, String[] names) {
    List<Class<? extends T>> classes = new LinkedList<Class<? extends T>>();
    
    for(String name : names) {
      try {
        classes.add((Class<? extends T>)Class.forName(name));
      } catch(ClassNotFoundException ex) {
        throw new ServiceConfigurationError(String.format("Cannot find class for name '%s'.", name), ex);
      }
    }
    
    return classes;
  }
  
  /**
   * Returns a list of classes, providing the requested service. Every of the returned classes
   * will extend the requested service class.
   * 
   * @param <T> The type of the services, you want to retrieve.
   * @param clazz The {@link Class baseclass}  of the services, you want to retrieve.
   * @return An {@link Iterable} of {@link Class classes} providing these services.
   */
  @SuppressWarnings("unchecked")
  public static <T> Iterable<Class<? extends T>> getServices(Class<T> clazz) {
    if(clazz == Codec.class) {
      return getClasses(clazz, CODEC_NAMES);
    } else if(clazz == PostingsFormat.class) {
      return getClasses(clazz, POSTING_FORMAT_NAMES);
    } else {
      throw new ServiceConfigurationError(String.format("Cannot load services for class '%s'."));
    }
  }
  
}