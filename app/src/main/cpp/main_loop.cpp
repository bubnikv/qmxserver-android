#include <stdlib.h>
#include <stdio.h>
#include <assert.h>

#include <string>
#include <vector>

#ifdef _WIN32
// Must be before <windows.h>, which libusb.h includes, to avoid conflicts with min/max macros and to suppress inclusion of legacy winsock headers.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#endif // _WIN32
#include <libusb.h>

#include <enet/enet.h>

#include "cat.h"

#define LOGD(S, ...) fprintf(stderr, (S), ##__VA_ARGS__)

/* The first PCM stereo AudioStreaming endpoint. */
#if 0
	#define EP_ISO_IN 0x82 // 0x84
	#define IFACE_NUM 2
#else
	#define EP_ISO_IN 0x83
	#define IFACE_NUM 3
#endif

//#define NUM_TRANSFERS 20 //10
#define NUM_TRANSFERS 5
//#define PACKET_SIZE (48*3*2)
#define PACKET_SIZE 300
#define NUM_PACKETS 20 // 10

//static int ipacket = 0;

static ENetAddress  g_address;
static ENetHost*    g_server = nullptr;

// ENet client data
struct Client
{
	std::string name;
};

// HDSDR ExtIO buffer len, multiples of 512.
// 5.3ms latency
#define EXT_BLOCKLEN (512)

    int receive_callback(int cnt, int status, float IQoffs, void* IQdata)
    {
        // 1) Push audio data to the clients.
        assert(cnt == -1 || cnt == 0 || cnt == EXT_BLOCKLEN);
        if (cnt == EXT_BLOCKLEN) {
            // Send a big 
            enet_host_broadcast(g_server, 0, enet_packet_create(IQdata, cnt * 2 * 2, 0));
        }
        return 0;
    }

void pump_enet_packets()
{
        // 2) Pump the UDP packets.
        for (;;) {
            ENetEvent event;
            int eventStatus = enet_host_service(g_server, &event, 0);
            if (eventStatus <= 0)
                break;
            switch (event.type) {
            case ENET_EVENT_TYPE_CONNECT:
                event.peer->data = new Client;
                {
                    char buf[2048];
                    if (! enet_address_get_host(&event.peer->address, buf, 2048)) {
                        auto ip = event.peer->address.host;
                        sprintf(buf, "%u.%u.%u.%u", ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
                    }
                    static_cast<Client*>(event.peer->data)->name = std::string(buf) + ":" + std::to_string(event.peer->address.port);
                }
                printf("(Server) We got a new connection from %x\n", event.peer->address.host);
                break;
            case ENET_EVENT_TYPE_RECEIVE:
                // Decode CatCommand
                if (event.channelID == 1 && event.packet->dataLength > 2) {
                    CatCommandID cmd;
                    memcpy(&cmd, event.packet->data, 2);
                    switch (cmd) {
                    case CatCommandID::SetFreq:
                        if (event.packet->dataLength == 10) {
                            int64_t frequency;
                            memcpy(&frequency, event.packet->data + 2, 8);
                            if (g_Cat.set_freq(frequency))
				    printf("set frequency succeeded\n");
			    else
				    printf("set frequency failed\n");
                        }
                        break;
                    case CatCommandID::SetCWTxFreq:
                        if (event.packet->dataLength == 10) {
                            int64_t frequency;
                            memcpy(&frequency, event.packet->data + 2, 8);
                            g_Cat.set_cw_tx_freq(frequency);
                        }
                        break;
                    case CatCommandID::SetCWKeyerSpeed:
                        if (event.packet->dataLength == 3) {
                            uint8_t cw_speed;
                            memcpy(&cw_speed, event.packet->data + 2, 1);
                            g_Cat.set_cw_keyer_speed(cw_speed);
                        }
                        break;
                    case CatCommandID::SetKeyerMode:
                        if (event.packet->dataLength == 3) {
                            uint8_t keyer_mode;
                            memcpy(&keyer_mode, event.packet->data + 2, 1);
                            g_Cat.set_cw_keyer_mode(KeyerMode(keyer_mode));
                        }
                        break;
                    case CatCommandID::SetAMPControl:
                        if (event.packet->dataLength == 10) {
                            bool    enabled;
                            int32_t delay, hang;
                            memcpy(&enabled, event.packet->data + 2, 1);
                            memcpy(&delay,   event.packet->data + 3, 4);
                            memcpy(&hang,    event.packet->data + 7, 4);
                            g_Cat.set_amp_control(enabled, delay, hang);
                        }
                        break;
                    case CatCommandID::SetIQBalanceAndPower:
                        if (event.packet->dataLength == 26) {
                            double phase_balance_deg, amplitude_balance, power;
                            memcpy(&phase_balance_deg,  event.packet->data + 2,  8);
                            memcpy(&amplitude_balance,  event.packet->data + 10, 8);
                            memcpy(&power,              event.packet->data + 18, 8);
                            g_Cat.setIQBalanceAndPower(phase_balance_deg, amplitude_balance, power);
                        }
                        break;
                    }
                }
                enet_packet_destroy(event.packet);
                break;
            case ENET_EVENT_TYPE_DISCONNECT:
                printf("%s disconnected.\n", static_cast<const Client*>(event.peer->data)->name.c_str());
                // Reset client's information.
                delete static_cast<const Client*>(event.peer->data);
                event.peer->data = nullptr;
                break;
            }
        }
}

static uint8_t data_buffer[EXT_BLOCKLEN * 2 * 2];
static int     data_buffer_len = 0;

static void cb_xfr(struct libusb_transfer *xfr)
{
	for (int ipacket = 0; ipacket < xfr->num_iso_packets; ++ ipacket) {
		struct libusb_iso_packet_descriptor *pack = &xfr->iso_packet_desc[ipacket];
		if (pack->status != LIBUSB_TRANSFER_COMPLETED) {
			LOGD("Error (status %d: %s) :", pack->status, libusb_error_name(pack->status));
			exit(1);
	    }
        if (pack->actual_length <= 0)
            continue;
	    const uint8_t *data = libusb_get_iso_packet_buffer_simple(xfr, ipacket);
#if 0
		for (int len = pack->length;;) {
			if (data_buffer_len + len >= EXT_BLOCKLEN * 2 * 2) {
				int num_copy = EXT_BLOCKLEN * 2 * 2 - data_buffer_len;
				memcpy(data_buffer + data_buffer_len, data, num_copy);
				receive_callback(EXT_BLOCKLEN, 0, 0.f, (void*)data_buffer);
				len -= num_copy;
				data += num_copy;
				data_buffer_len = 0;
			} else {
				memcpy(data_buffer + data_buffer_len, data, len);
				data_buffer_len += len;
				break;
			}
		}
#else
		assert((pack->actual_length % 6) == 0);
		for (int len = pack->actual_length / 6;;) {
			if (data_buffer_len + len >= EXT_BLOCKLEN) {
				int num_copy = EXT_BLOCKLEN - data_buffer_len;
				for (int i = 0; i < num_copy; ++ i) {
					int j = 4 * (data_buffer_len + i);
					data ++; // skip LSB
					data_buffer[j + 2] = *data ++;
					data_buffer[j + 3] = *data ++;
					data ++; // skip LSB
					data_buffer[j + 0] = *data ++;
					data_buffer[j + 1] = *data ++;
				}
				receive_callback(EXT_BLOCKLEN, 0, 0.f, (void*)data_buffer);
				len -= num_copy;
				data_buffer_len = 0;
			} else {
				for (int i = 0; i < len; ++ i) {
					int j = 4 * (data_buffer_len + i);
					data ++; // skip LSB
					data_buffer[j + 2] = *data ++;
					data_buffer[j + 3] = *data ++;
					data ++; // skip LSB
					data_buffer[j + 0] = *data ++;
					data_buffer[j + 1] = *data ++;
				}
				data_buffer_len += len;
				break;
			}
		}
#endif
	#if 0
		if (++ ipacket == 100) {
			printf("\n");
			ipacket = 0;
		}
		if (ipacket % 10 == 0)
			printf(".");
	#endif
	}

	if (int err = libusb_submit_transfer(xfr); err < 0) {
		LOGD("error re-submitting URB: %d\n", err);
		exit(1);
	}
}

static int benchmark_in(libusb_device_handle *devh, uint8_t ep)
{
	static uint8_t buf[PACKET_SIZE * NUM_PACKETS];
	static struct libusb_transfer *xfr[NUM_TRANSFERS];
	int num_iso_pack = NUM_PACKETS;
	int i;
    for (i=0; i<NUM_TRANSFERS; i++) {
	    xfr[i] = libusb_alloc_transfer(num_iso_pack);
	    if (!xfr[i]) {
	            LOGD("Could not allocate transfer");
       		    return -1;
	    }
		libusb_fill_iso_transfer(xfr[i], devh, ep, buf, sizeof(buf), num_iso_pack, cb_xfr, NULL, 1000);
		libusb_set_iso_packet_lengths(xfr[i], sizeof(buf)/num_iso_pack);
		int r = libusb_submit_transfer(xfr[i]);
		if (r < 0) {
			LOGD("error re-submitting URB: %d\n", r);
			exit(1);
		}
	}
	//gettimeofday(&tv_start, NULL);
	return 1;
}

#define LIBUSB_ANDROID

int main_loop(int fd, const std::string &device_path)
{ 
	libusb_context *context;
	libusb_device_handle * dev_handle;
	libusb_device *device;
	struct libusb_device_descriptor desc;
	unsigned char buffer[256];
	
	int rc = libusb_init(&context);
	if (rc < 0) {
		LOGD("Error initializing libusb: %s\n", libusb_error_name(rc));
		return 1;
	}

	const UsbDeviceDescriptor descriptor = UsbDevQrpLabs; // UsbDevPeaberry;
	UsbDeviceDetected		  detected;

#ifdef LIBUSB_ANDROID
#if 0
	rc = libusb_wrap_sys_device(context, (intptr_t)fd, &dev_handle);
#else
    device = libusb_get_device2(context, device_path.c_str());
    if (! device) {
        LOGD("Error opening Android USB device %s: %s\n", device_path.c_str(), libusb_error_name(rc));
        return 1;
    }
    rc = libusb_open2(device, &dev_handle, (intptr_t)fd);
#endif
	if (rc < 0) {
		LOGD("Error opening Android USB file handle %d: %s\n", (int)fd, libusb_error_name(rc));
		return 1;
	}
	try {
		detected = match_libusb_descriptor(dev_handle, descriptor);
	} catch (const std::exception& e) {
		printf("match_libusb_descriptor(): %s\n", e.what());
	}
#else // LIBUSB_ANDROID
	try {
		std::vector<UsbDeviceDetected> detected_all = find_libusb_devices(context, descriptor);
		if (detected_all.empty()) {
			LOGD("USB device was not found\n");
			return 1;
		}
		detected = detected_all.front();
		dev_handle = detected.handle;
	} catch (const std::exception& e) {
		LOGD("find_libusb_devices(): %s\n", e.what());
		return 1;
	}
#endif // LIBUSB_ANDROID

	printf("Vendor ID: %04x\n",  descriptor.vendor_id);
	printf("Product ID: %04x\n", descriptor.product_id);
	printf("Vendor: %s\n",       descriptor.vendor_name);
	printf("Product Name: %s\n", std::string(detected.product_name).c_str());
	printf("Serial No: %s\n",	 detected.serial_number.c_str());

    // 1: CDC
    // 2: Audio control
    // 2: Audio in
    // 3: Audio out
	for (int iface : { 0, 1, 2, 3, 4 }) {
#ifndef _WIN32
		rc = libusb_kernel_driver_active(dev_handle, iface);
		if (rc < 0) {
			LOGD("libusb_kernel_driver_active failed: %s\n", libusb_error_name(rc));
			return 1;
		}
		if (rc == 1) {
			printf("Detaching kernel driver\n");
			rc = libusb_detach_kernel_driver(dev_handle, iface);
			if (rc < 0) {
				LOGD("Could not detach kernel driver: %s\n", libusb_error_name(rc));
				return 1;
			}
		}
#endif // _WIN32
		printf("Claiming interface %x\n", iface);
		rc = libusb_claim_interface(dev_handle, iface);
		if (rc < 0) {
			LOGD("Error claiming interface: %s\n", libusb_error_name(rc));
			return 1;
		}
	}

	rc = libusb_set_interface_alt_setting(dev_handle, IFACE_NUM, 1);
	if (rc < 0) {
		LOGD("Error setting alt setting: %s\n", libusb_error_name(rc));
		return 1;
	}

    if (enet_initialize() != 0) {
		LOGD("An error occured while initializing ENet.\n");
		return 1;
	}
	g_address.host = ENET_HOST_ANY;
	g_address.port = 1234;
	{
		static const int max_clients = 32;
		static const int max_channels = 2;
        g_server = enet_host_create(&g_address, max_clients, max_channels, 0, 0);
	}
    if (g_server == nullptr) {
		LOGD("An error occured while trying to create an ENet server host\n");
		return 1;
	}

	g_Cat.init(dev_handle);

	benchmark_in(dev_handle, EP_ISO_IN);

	for (;;) {
		rc = libusb_handle_events(context);
		if (rc != LIBUSB_SUCCESS)
			break;
		pump_enet_packets();
	}

	libusb_exit(context);
}
