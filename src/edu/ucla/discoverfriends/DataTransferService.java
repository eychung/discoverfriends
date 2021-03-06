package edu.ucla.discoverfriends;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import edu.ucla.common.Constants;
import edu.ucla.common.Utils;
import edu.ucla.encryption.AES;
import edu.ucla.encryption.PKE;

/**
 * @author Eric Chung
 * 
 * A service that process each data transfer request. Encryption of the files
 * are done here before they are sent through the network.
 * 
 * Each packet is sent in the following encoding:
 * size_of_data_1_in_bytes, ..., size_of_data_n_in_byte, data_1, ..., data_n
 */
public class DataTransferService extends IntentService {

	private static final String TAG = DataTransferService.class.getName();

	private WifiManager mWifi;

	public DataTransferService(String name) {
		super(name);
	}

	public DataTransferService() {
		super("DataTransferService");
	}

	/**
	 * Calculate the broadcast IP we need to send the packet along. If we send it
	 * to 255.255.255.255, it never gets sent. I guess this has something to do
	 * with the mobile network not wanting to do broadcast.
	 */
	private InetAddress getBroadcastAddress() throws IOException {
		mWifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		DhcpInfo dhcp = mWifi.getDhcpInfo();
		if (dhcp == null) {
			Log.d(TAG, "Could not get DHCP information.");
			return null;
		}

		int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
		byte[] quads = new byte[4];
		for (int k = 0; k < 4; k++)
			quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
		return InetAddress.getByAddress(quads);
	}

	/*
	 * (non-Javadoc)
	 * @see android.app.IntentService#onHandleIntent(android.content.Intent)
	 */
	@Override
	protected void onHandleIntent(Intent intent) {
		Log.i(TAG, "Enters handle.");
		if (intent.getAction().equals(Constants.TEST)) {
			Log.i(TAG, "Finished intent");
		}
		// At network setup, UDP broadcasts (BF, BF+, CF) and waits for encrypted CF.
		else if (intent.getAction().equals(Constants.NETWORK_INITIATOR_SETUP)) {
			try {
				Log.i(TAG, "Creating socket.");
				DatagramSocket socket = new DatagramSocket(Constants.PORT);
				socket.setBroadcast(true);
				socket.setSoTimeout(Constants.SOCKET_TIMEOUT);
				Log.i(TAG, "Finished setting up socket.");
				try {
					// Broadcast message.
					ByteArrayOutputStream byteStream = new ByteArrayOutputStream(Constants.BYTE_ARRAY_SIZE);
					ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(byteStream));
					outputStream.writeObject(intent.getExtras().getSerializable(Constants.EXTRAS_SNP));
					outputStream.flush();
					Log.i(TAG, "Wrote SNP object.");
					byte[] payload = byteStream.toByteArray();
					int byteCount = payload.length;
					byte[] payloadSize = new byte[4];

					// int -> byte[]
					for (int i = 0; i < 4; ++i) {
						int shift = i << 3; // i * 8
						payloadSize[3-i] = (byte)((byteCount & (0xff << shift)) >>> shift);
					}

					// Send the payload size.
					DatagramPacket packet = new DatagramPacket(
							payloadSize, 4, getBroadcastAddress(), Constants.PORT);
					socket.send(packet);
					Log.i(TAG, "Sent size packet.");

					// Send the payload.
					packet = new DatagramPacket(
							payload, payload.length, getBroadcastAddress(), Constants.PORT);
					socket.send(packet);
					Log.i(TAG, "Broadcasted server setup message.");

					// Wait for connected clients to send back their own encrypted certificate and MAC address until SOCKET_TIMEOUT.
					try {
						while (true) {
							// Get byte size of certificate.
							byte[] data = new byte[4];
							packet = new DatagramPacket(data, data.length);
							socket.receive(packet);
							int ecfSize = 0;
							for (int i = 0; i < 4; ++i) {
								ecfSize |= (data[3-i] & 0xff) << (i << 3);
							}

							// Get byte size of MAC address.
							data = new byte[4];
							packet = new DatagramPacket(data, data.length);
							socket.receive(packet);
							int macAddressSize = 0;
							for (int i = 0; i < 4; ++i) {
								macAddressSize |= (data[3-i] & 0xff) << (i << 3);
							}

							// Get encrypted certificate.
							byte[] packetSize = new byte[ecfSize];
							packet = new DatagramPacket(packetSize, packetSize.length);
							socket.receive(packet);
							byte[] encryptedCertificate = packet.getData();
							Log.i(TAG, "Received encrypted certificate.");

							// Get MAC address.
							packetSize = new byte[macAddressSize];
							packet = new DatagramPacket(packetSize, packetSize.length);
							socket.receive(packet);
							byte[] macAddress = packet.getData();
							Log.i(TAG, "Received MAC address.");

							// Broadcast packet back to calling activity.
							intent = new Intent();
							intent.setAction(Constants.NETWORK_INITIATOR_GET_SETUP_ENCRYPTED_CERTIFICATE_RECEIVED);
							intent.putExtra(Constants.EXTRAS_ENCRYPTED_CERTIFICATE, encryptedCertificate);
							intent.putExtra(Constants.EXTRAS_MAC_ADDRESS, Utils.byteToString(macAddress));
							intent.putExtra(Constants.EXTRAS_SENDER_IP, packet.getAddress().toString());
							sendBroadcast(intent);
						}
					} catch (SocketTimeoutException e) {
						Log.d(TAG, "Receive timed out.");
					}
				} catch (IOException e) {
					Log.e(TAG, e.getMessage());
				} finally {
					socket.close();
				}
			} catch (SocketException e) {
				Log.e(TAG, e.getMessage());
			}
		}

		// Initiator sends the list of certificates to each user. This call is done multiple times.
		else if (intent.getAction().equals(Constants.NETWORK_INITIATOR_SETUP_CERTIFICATE_LIST)) {
			try {
				InetAddress address = InetAddress.getByName(intent.getStringExtra(Constants.EXTRAS_SENDER_IP));
				DatagramSocket socket = new DatagramSocket(Constants.PORT, address);
				socket.setSoTimeout(Constants.SOCKET_TIMEOUT);
				try {
					ByteArrayOutputStream byteStream = new ByteArrayOutputStream(Constants.BYTE_ARRAY_SIZE);
					ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(byteStream));
					outputStream.writeObject(intent.getExtras().getSerializable(Constants.EXTRAS_CERTIFICATE_LIST));
					outputStream.flush();

					String initiatorId = intent.getStringExtra(Constants.EXTRAS_USER_ID);
					String hashedInitiatorUid = Utils.hash(initiatorId);
					byte[] key = Utils.charToByte(hashedInitiatorUid.toCharArray());
					byte[] crtList = byteStream.toByteArray();
					byte[] encryptedCrtList = AES.encrypt(key, crtList);

					int byteCount = encryptedCrtList.length;
					byte[] payloadSize = new byte[4];

					// int -> byte[]
					for (int i = 0; i < 4; ++i) {
						int shift = i << 3; // i * 8
						payloadSize[3-i] = (byte)((byteCount & (0xff << shift)) >>> shift);
					}

					// Send the encrypted certificate list size.
					DatagramPacket packet = new DatagramPacket(payloadSize, 4, address, Constants.PORT);
					socket.send(packet);

					// Send the encrypted certificate list.
					packet = new DatagramPacket(encryptedCrtList, encryptedCrtList.length, address, Constants.PORT);
					socket.send(packet);
				} catch (IOException e) {
					Log.e(TAG, e.getMessage());
				} catch (NoSuchAlgorithmException e) {
					Log.e(TAG, e.getMessage());
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
				} finally {
					socket.close();
				}
			} catch (SocketException e) {
				Log.e(TAG, e.getMessage());
			} catch (UnknownHostException e) {
				Log.e(TAG, e.getMessage());
			}
		}

		// Initiator connects to a specific target and sends an encrypted key and encrypted message
		// or encrypted key and encrypted certificate. This scheme follows hybrid encryption.
		else if (intent.getAction().equals(Constants.NETWORK_INITIATOR_KEY_AND_MESSAGE) ||
				intent.getAction().equals(Constants.NETWORK_INITIATOR_KEY_AND_CERTIFICATE)) {
			try {
				InetAddress address = InetAddress.getByName(intent.getStringExtra(Constants.EXTRAS_SENDER_IP));
				DatagramSocket socket = new DatagramSocket(Constants.PORT, address);
				socket.setBroadcast(true);
				socket.setSoTimeout(Constants.SOCKET_TIMEOUT);
				try {
					// Encrypt symmetric key with public key.
					byte[] symmetricKey = intent.getByteArrayExtra(Constants.EXTRAS_SYMMETRIC_KEY_ENCODED);
					PublicKey publicKey = (PublicKey) intent.getSerializableExtra(Constants.EXTRAS_PUBLIC_KEY);
					byte[] encryptedKey = PKE.encrypt(publicKey, symmetricKey);

					int byteCount = encryptedKey.length;
					byte[] payloadSize = new byte[4];

					// int -> byte[]
					for (int i = 0; i < 4; ++i) {
						int shift = i << 3; // i * 8
						payloadSize[3-i] = (byte)((byteCount & (0xff << shift)) >>> shift);
					}

					// Send the encrypted key size.
					DatagramPacket packet = new DatagramPacket(payloadSize, 4, address, Constants.PORT);
					socket.send(packet);

					// Send the encrypted key.
					packet = new DatagramPacket(encryptedKey, encryptedKey.length, address, Constants.PORT);
					socket.send(packet);


					// Encrypt message or certificate with AES.
					byte[] encrypted;
					if (intent.getAction().equals(Constants.NETWORK_INITIATOR_KEY_AND_MESSAGE)) {
						String message = intent.getStringExtra(Constants.EXTRAS_MESSAGE);
						byte[] plaintext = Utils.charToByte(message.toCharArray());
						encrypted = AES.encrypt(symmetricKey, plaintext);
					}
					else {
						X509Certificate certificate = (X509Certificate) intent.getSerializableExtra(Constants.EXTRAS_CERTIFICATE);
						ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream(Constants.BYTE_ARRAY_SIZE);
						ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(byteOutputStream));
						outputStream.writeObject(certificate);
						outputStream.flush();
						byte[] payload = byteOutputStream.toByteArray();
						encrypted = AES.encrypt(symmetricKey, payload);
					}

					byteCount = encrypted.length;
					payloadSize = new byte[4];

					// int -> byte[]
					for (int i = 0; i < 4; ++i) {
						int shift = i << 3; // i * 8
						payloadSize[3-i] = (byte)((byteCount & (0xff << shift)) >>> shift);
					}

					// Send the encrypted message or encrypted certificate size.
					packet = new DatagramPacket(payloadSize, 4, address, Constants.PORT);
					socket.send(packet);

					// Send the encrypted message or encrypted certificate.
					packet = new DatagramPacket(encrypted, encrypted.length, address, Constants.PORT);
					socket.send(packet);
					Log.i(TAG, "Broadcasted initiator message.");
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
				} finally {
					socket.close();
				}
			} catch (SocketException e) {
				Log.e(TAG, e.getMessage());
			} catch (UnknownHostException e) {
				Log.e(TAG, e.getMessage());
			}
		}

		// Using UDP, target sends an encrypted message or encrypted certificate to initiator.
		else if (intent.getAction().equals(Constants.NETWORK_TARGET_MESSAGE) || 
				intent.getAction().equals(Constants.NETWORK_TARGET_SEND_CERTIFICATE)) {
			try {
				DatagramSocket socket = new DatagramSocket(Constants.PORT);
				socket.setSoTimeout(Constants.SOCKET_TIMEOUT);
				try {
					byte[] key = ((PublicKey) intent.getSerializableExtra(Constants.EXTRAS_PUBLIC_KEY)).getEncoded();
					byte[] encrypted = null;
					// Encrypt message with AES.
					if (intent.getAction().equals(Constants.NETWORK_TARGET_MESSAGE)) {
						String message = intent.getStringExtra(Constants.EXTRAS_MESSAGE);
						byte[] plaintext = Utils.charToByte(message.toCharArray());
						encrypted = AES.encrypt(key, plaintext);
					}

					// Encrypt certificate with AES.
					else if (intent.getAction().equals(Constants.NETWORK_TARGET_SEND_CERTIFICATE)) {
						ByteArrayOutputStream byteStream = new ByteArrayOutputStream(Constants.BYTE_ARRAY_SIZE);
						ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(byteStream));
						outputStream.writeObject(intent.getSerializableExtra(Constants.EXTRAS_CERTIFICATE));
						outputStream.flush();
						byte[] crt = byteStream.toByteArray();
						encrypted = AES.encrypt(key, crt);
					}

					int byteCount = encrypted.length;
					byte[] payloadSize = new byte[4];

					// int -> byte[]
					for (int i = 0; i < 4; ++i) {
						int shift = i << 3; // i * 8
						payloadSize[3-i] = (byte)((byteCount & (0xff << shift)) >>> shift);
					}

					// Send the encrypted message size.
					DatagramPacket packet = new DatagramPacket(
							payloadSize, 4, getBroadcastAddress(), Constants.PORT);
					socket.send(packet);

					// Send the encrypted message.
					packet = new DatagramPacket(
							encrypted, encrypted.length, getBroadcastAddress(), Constants.PORT);
					socket.send(packet);
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
				} finally {
					socket.close();
				}
			} catch (SocketException e) {
				Log.e(TAG, e.getMessage());
			}
		}

	}

}
