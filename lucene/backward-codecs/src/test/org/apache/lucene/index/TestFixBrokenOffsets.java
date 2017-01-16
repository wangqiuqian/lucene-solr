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
package org.apache.lucene.index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

public class TestFixBrokenOffsets extends LuceneTestCase {

  // Run this in Lucene 6.x:
  //
  //     ant test -Dtestcase=TestFixBrokenOffsets -Dtestmethod=testCreateBrokenOffsetsIndex -Dtests.codec=default -Dtests.useSecurityManager=false
  /*
  public void testCreateBrokenOffsetsIndex() throws IOException {

    Path indexDir = Paths.get("/tmp/brokenoffsets");
    Files.deleteIfExists(indexDir);
    Directory dir = newFSDirectory(indexDir);
    IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

    Document doc = new Document();
    FieldType fieldType = new FieldType(TextField.TYPE_STORED);
    fieldType.setStoreTermVectors(true);
    fieldType.setStoreTermVectorPositions(true);
    fieldType.setStoreTermVectorOffsets(true);
    Field field = new Field("foo", "bar", fieldType);
    field.setTokenStream(new CannedTokenStream(new Token("foo", 10, 13), new Token("foo", 7, 9)));
    doc.add(field);
    writer.addDocument(doc);
    writer.commit();

    // 2nd segment
    doc = new Document();
    field = new Field("foo", "bar", fieldType);
    field.setTokenStream(new CannedTokenStream(new Token("bar", 15, 17), new Token("bar", 1, 5)));
    doc.add(field);
    writer.addDocument(doc);
    
    writer.close();

    dir.close();
  }
  */

  public void testFixBrokenOffsetsIndex() throws IOException {
    InputStream resource = getClass().getResourceAsStream("index.630.brokenoffsets.zip");
    assertNotNull("Broken offsets index not found", resource);
    Path path = createTempDir("brokenoffsets");
    TestUtil.unzip(resource, path);
    Directory dir = newFSDirectory(path);

    // OK: index is 6.3.0 so offsets not checked:
    TestUtil.checkIndex(dir);
    
    MockDirectoryWrapper tmpDir = newMockDirectory();
    tmpDir.setCheckIndexOnClose(false);
    IndexWriter w = new IndexWriter(tmpDir, new IndexWriterConfig());
    w.addIndexes(dir);
    w.close();
    // OK: addIndexes(Directory...) also keeps version as 6.3.0, so offsets not checked:
    TestUtil.checkIndex(tmpDir);
    tmpDir.close();

    final MockDirectoryWrapper tmpDir2 = newMockDirectory();
    tmpDir2.setCheckIndexOnClose(false);
    w = new IndexWriter(tmpDir2, new IndexWriterConfig());
    DirectoryReader reader = DirectoryReader.open(dir);
    List<LeafReaderContext> leaves = reader.leaves();
    CodecReader[] codecReaders = new CodecReader[leaves.size()];
    for(int i=0;i<leaves.size();i++) {
      codecReaders[i] = (CodecReader) leaves.get(i).reader();
    }
    w.addIndexes(codecReaders);
    reader.close();
    w.close();

    // NOT OK: broken offsets were copied into a 7.0 segment:
    ByteArrayOutputStream output = new ByteArrayOutputStream(1024);    
    RuntimeException re = expectThrows(RuntimeException.class, () -> {TestUtil.checkIndex(tmpDir2, false, true, output);});
    assertEquals("term [66 6f 6f]: doc 0: pos 1: startOffset 7 < lastStartOffset 10; consider using the FixBrokenOffsets tool in Lucene's backward-codecs module to correct your index", re.getMessage());
    tmpDir2.close();

    // Now run the tool and confirm the broken offsets are fixed:
    Path path2 = createTempDir("fixedbrokenoffsets").resolve("subdir");
    FixBrokenOffsets.main(new String[] {path.toString(), path2.toString()});
    Directory tmpDir3 = FSDirectory.open(path2);
    TestUtil.checkIndex(tmpDir3);
    tmpDir3.close();
    
    dir.close();
  }
}
