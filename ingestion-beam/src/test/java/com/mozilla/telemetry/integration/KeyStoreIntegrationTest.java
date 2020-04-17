package com.mozilla.telemetry.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRing;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.LocationName;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.testing.RemoteStorageHelper;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.google.protobuf.ByteString;
import com.mozilla.telemetry.decoder.DecryptPioneerPayloads;
import com.mozilla.telemetry.util.Json;
import com.mozilla.telemetry.util.KeyStore;
import com.mozilla.telemetry.util.TestWithDeterministicJson;
import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.PrivateKey;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.util.MimeTypes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Test the KeyStore using Google Cloud KMS.
 *
 * <p>First, resources are staged in a local temporary folder. Here, we encrypt the
 * keys using KMS. Then we synchronize the folder with a temporary bucket
 * created in GCS. We programmatically generate a metadata file that we will
 * test. Finally we cover all code paths related to decrypting the private keys.
 */
public class KeyStoreIntegrationTest extends TestWithDeterministicJson {

  private Storage storage;
  private String projectId;
  private String bucket;
  private final String keyRingId = "test-ingestion-beam-integration";

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  /** Create a storage bucket for metadata and keys. */
  @Before
  public void createBucket() {
    RemoteStorageHelper storageHelper = RemoteStorageHelper.create();
    storage = storageHelper.getOptions().getService();
    projectId = storageHelper.getOptions().getProjectId();
    bucket = RemoteStorageHelper.generateBucketName();
    storage.create(BucketInfo.of(bucket));
  }

  /** Clean up storage resources. */
  @After
  public void deleteBucket() throws Exception {
    RemoteStorageHelper.forceDelete(storage, bucket, 5, TimeUnit.SECONDS);
  }

  /**
   * Creates key rings and crypto keys in KMS if they do not exist. These
   * objects are immutable and cannot be deleted once they are created, so use
   * this function with caution.
   */
  private void ensureKmsResources(KeyManagementServiceClient client, String resourceId) {
    CryptoKeyName name = CryptoKeyName.parse(resourceId);
    assertEquals(projectId, name.getProject());
    assertEquals("global", name.getLocation());
    assertEquals(keyRingId, name.getKeyRing());

    // getOrCreateKeyRing
    KeyRingName keyRingName = KeyRingName.of(projectId, "global", name.getKeyRing());
    try {
      client.getKeyRing(keyRingName);
    } catch (NotFoundException e) {
      LocationName parent = LocationName.of(projectId, "global");
      KeyRing request = KeyRing.newBuilder().build();
      client.createKeyRing(parent, name.getCryptoKey(), request);
    }

    // getOrCreateCryptoKey
    CryptoKeyName cryptoKeyName = CryptoKeyName.of(projectId, "global", name.getKeyRing(),
        name.getCryptoKey());
    try {
      client.getCryptoKey(cryptoKeyName);
    } catch (NotFoundException e) {
      CryptoKey request = CryptoKey.newBuilder()
          .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).build();
      client.createCryptoKey(keyRingName, name.getCryptoKey(), request);
    }
  }

  private byte[] encrypt(KeyManagementServiceClient client, String resourceId, byte[] data)
      throws Exception {
    return client.encrypt(resourceId, ByteString.copyFrom(data)).getCiphertext().toByteArray();
  }

  private byte[] decrypt(KeyManagementServiceClient client, String resourceId, byte[] data)
      throws Exception {
    return client.decrypt(resourceId, ByteString.copyFrom(data)).getPlaintext().toByteArray();
  }

  /**
  * Write to cloud storage using the FileSystems API. See https://stackoverflow.com/a/50050583.
  */
  private void writeToStorage(String path, byte[] data) throws Exception {
    ResourceId resourceId = FileSystems.matchNewResource(path, false);
    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        ReadableByteChannel readerChannel = Channels.newChannel(inputStream);
        WritableByteChannel writerChannel = FileSystems.create(resourceId, MimeTypes.TEXT)) {
      ByteStreams.copy(readerChannel, writerChannel);
    }
  }

  /**
   * Upload a metadata file and the referenced private keys to their testing
   * locations. The resource is a templated metadata json file. "DUMMY_*"
   * variables are replaced with their corresponding locations. This also
   * encrypts the private keys and ensures that the KMS resources are created if
   * specified.
   */
  private String prepareKeyStoreMetadata(String resource, boolean shouldEncrypt) throws Exception {
    // enable gs support
    FileSystems.setDefaultPipelineOptions(PipelineOptionsFactory.create());

    byte[] data = Resources.toByteArray(Resources.getResource(resource));
    ArrayNode nodes = Json.readArrayNode(data);
    for (JsonNode node : nodes) {
      // replace dummy values with values related to integration testing
      String kmsResourceId = node.get("kms_resource_id").textValue().replace("DUMMY_PROJECT_ID",
          projectId);

      // The path may be on the local filesystem or in cloud storage by
      // referencing a variable to be replaced.
      String privateKeyUri = node.get("private_key_uri").textValue().replace("DUMMY_BUCKET", bucket)
          .replace("DUMMY_TEMP_FOLDER", tempFolder.getRoot().toString());
      ((ObjectNode) node).put("kms_resource_id", kmsResourceId);
      ((ObjectNode) node).put("private_key_uri", privateKeyUri);

      String keyId = node.get("document_namespace").textValue();
      byte[] key = Resources
          .toByteArray(Resources.getResource(String.format("pioneer/%s.private.json", keyId)));

      // optionally encrypt the private key resources and upload to testing location
      if (shouldEncrypt) {
        try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
          ensureKmsResources(client, kmsResourceId);
          byte[] encryptedKey = encrypt(client, kmsResourceId, key);
          writeToStorage(privateKeyUri, encryptedKey);
        }
      } else {
        writeToStorage(privateKeyUri, key);
      }
    }
    assertFalse(nodes.asText().contains("DUMMY_PROJECT_ID")
        || nodes.asText().contains("DUMMY_BUCKET") || nodes.asText().contains("DUMMY_TEMP_FOLDER"));

    String keyStoreMetadata = String.format("gs://%s/metadata.json", bucket);
    writeToStorage(keyStoreMetadata, nodes.toString().getBytes());
    return keyStoreMetadata;
  }

  /**
   * Ensure KMS permissions are configured as expected. This ensures a key ring
   * and crypto key exist. It then fetches the public key associated to the
   * crypto key and encodes a small string. Then the KMS api is called to
   * decrypt the message. This test (and following tests) require Cloud KMS
   * Admin, CryptoKey Decrypter, and Public Key Viewer.
   */
  @Test
  public void testKmsConfigured() throws Exception {
    // encrypt a realistically sized payload
    byte[] plainText = Resources
        .toByteArray(Resources.getResource("pioneer/study-foo.private.json"));
    String cryptoKeyId = "test-kms-configured";
    String resourceId = CryptoKeyName.of(projectId, "global", keyRingId, cryptoKeyId).toString();
    try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
      ensureKmsResources(client, resourceId);
      byte[] cipherText = encrypt(client, resourceId, plainText);
      byte[] decrypted = decrypt(client, resourceId, cipherText);
      assertEquals(plainText, decrypted);
    }
  }

  @Test
  public void testKeyStoreReadsPlaintextPrivateKeyFromCloudStorage() throws Exception {
    String keyStoreMetadata = prepareKeyStoreMetadata("pioneer/metadata-integration.json", false);
    KeyStore store = KeyStore.of(keyStoreMetadata);
    assertNotEquals(null, store.getKey("study-foo"));
    assertNotEquals(null, store.getKey("study-bar"));
  }

  @Test
  public void testKeyStoreEncryptedKeysCanDecryptPayload() throws Exception {
    String keyStoreMetadata = prepareKeyStoreMetadata("pioneer/metadata-integration.json", true);
    KeyStore store = KeyStore.of(keyStoreMetadata);
    PrivateKey key = store.getKey("study-foo");
    byte[] data = Resources.toByteArray(Resources.getResource("pioneer/study-foo.ciphertext.json"));
    byte[] expect = Resources
        .toByteArray(Resources.getResource("pioneer/study-foo.plaintext.json"));
    byte[] actual = DecryptPioneerPayloads.decrypt(key, new String(data));
    assertEquals(expect, actual);
  }
}
