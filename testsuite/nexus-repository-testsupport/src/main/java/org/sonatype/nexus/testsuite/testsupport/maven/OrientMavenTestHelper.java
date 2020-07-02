/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.testsuite.testsupport.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.annotation.Priority;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.orient.maven.MavenFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.internal.MavenMimeRulesSource;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.storage.TempBlob;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.hash.HashCode;
import com.google.common.io.CharStreams;
import org.joda.time.DateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter.P_NAME;

@FeatureFlag(name = "nexus.orient.store.content")
@Named
@Singleton
@Priority(Integer.MAX_VALUE)
public class OrientMavenTestHelper
    extends MavenTestHelper
{
  @Override
  public void verifyHashesExistAndCorrect(final Repository repository, final String path) throws Exception {
    MavenFacet mavenFacet = repository.facet(MavenFacet.class);
    MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(path);
    UnitOfWork.begin(repository.facet(StorageFacet.class).txSupplier());
    try {
      Content content = mavenFacet.get(mavenPath);
      assertThat(content, notNullValue());
      Map<HashAlgorithm, HashCode> hashCodes =
          content.getAttributes().require(Content.CONTENT_HASH_CODES_MAP, Content.T_CONTENT_HASH_CODES_MAP);
      for (HashType hashType : HashType.values()) {
        Content contentHash = mavenFacet.get(mavenPath.hash(hashType));
        String storageHash = hashCodes.get(hashType.getHashAlgorithm()).toString();
        assertThat(storageHash, notNullValue());
        try (InputStream is = contentHash.openInputStream()) {
          String mavenHash = CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
          assertThat(storageHash, equalTo(mavenHash));
        }
      }
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Override
  public void writeWithoutValidation(
      final Repository repository,
      final String path,
      final Payload payload) throws IOException
  {
    MavenFacet mavenFacet = repository.facet(MavenFacet.class);
    StorageFacet storageFacet = repository.facet(StorageFacet.class);

    MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(path);
    UnitOfWork.begin(repository.facet(StorageFacet.class).txSupplier());
    try {
      try (TempBlob tempBlob = storageFacet.createTempBlob(payload, HashType.ALGORITHMS)) {

        mavenFacet.put(mavenPath, tempBlob, MavenMimeRulesSource.METADATA_TYPE, new AttributesMap());
      }
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Override
  public void write(final Repository repository, final String path, final Payload payload) throws IOException {
    MavenFacet mavenFacet = repository.facet(MavenFacet.class);
    MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(path);
    UnitOfWork.begin(repository.facet(StorageFacet.class).txSupplier());
    try {
      mavenFacet.put(mavenPath, payload);
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Override
  public Payload read(final Repository repository, final String path) throws IOException {
    MavenFacet mavenFacet = repository.facet(MavenFacet.class);
    MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(path);
    UnitOfWork.begin(repository.facet(StorageFacet.class).txSupplier());
    try {
      return mavenFacet.get(mavenPath);
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Override
  public DateTime getLastDownloadedTime(final Repository repository, final String assetPath) {
    MavenFacet mavenFacet = repository.facet(MavenFacet.class);
    MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(assetPath);
    try (StorageTx tx = repository.facet(StorageFacet.class).txSupplier().get()) {
      tx.begin();

      Asset asset = tx.findAssetWithProperty(P_NAME, mavenPath.getPath(), tx.findBucket(repository));

      return asset.lastDownloaded();
    }
  }
}
