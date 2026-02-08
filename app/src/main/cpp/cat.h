// Peaberry CW - Transceiver for Peaberry SDR
// Copyright (C) 2015 David Turnbull AE9RB
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

#ifndef CAT_H
#define CAT_H

#include <string>
#include <string_view>
#include <vector>
#include <cstdint>

#ifdef _WIN32
    // Must be before <windows.h>, which libusb.h includes, to avoid conflicts with min/max macros and to suppress inclusion of legacy winsock headers.
    #define WIN32_LEAN_AND_MEAN
    #define NOMINMAX
#endif // _WIN32
#include <libusb.h>

#include "Config.h"

struct UsbDeviceDescriptor {
    std::uint16_t      vendor_id;
    std::uint16_t      product_id;
    const char*        vendor_name;
    const char* const* product_names;
    std::size_t        product_name_count;
};

constexpr const char* PeaberryProducts[] = {
    "Peaberry SDR"
};

constexpr UsbDeviceDescriptor UsbDevPeaberry {
    0x16C0,                 // VOTI / OBDEV VID
    0x05DC,                 // OBDEV PID
    "www.obdev.at",
    PeaberryProducts,
    sizeof(PeaberryProducts) / sizeof(PeaberryProducts[0])
};

constexpr const char* QrpLabsProducts[] = {
    "QMX Transceiver",
    "QDX Transceiver"
};

constexpr UsbDeviceDescriptor UsbDevQrpLabs {
    0x0483,                 // STMicroelectronics VID
    0xA34C,                 // QRP Labs QMX Transceiver PID
    "QRP Labs",
    QrpLabsProducts,
    sizeof(QrpLabsProducts) / sizeof(QrpLabsProducts[0])
};

struct UsbDeviceDetected
{
    std::string_view      product_name;
    std::string           serial_number;
    libusb_device_handle *handle;
};

UsbDeviceDetected match_libusb_descriptor(libusb_device_handle *dev_handle, const UsbDeviceDescriptor &descriptor);

// Return libusb devices detected with given descriptor.
// Multiple devices could be detected.
// In any way, caller is responsible for closing all libusb handles returned.
// Throws std::runtime_error()
std::vector<UsbDeviceDetected> find_libusb_devices(
    libusb_context *ctxt, const UsbDeviceDescriptor &descriptor, 
    std::string_view serial_number = std::string_view(), bool first_only = false);

#define IAMBIC_MODE_B       (1 << 0)
#define IAMBIC_SKEY         (1 << 1)
#define IAMBIC_AUTOSPACE    (1 << 2)
#define IAMBIC_RST_N        (1 << 7)

enum class CatCommandID : uint16_t {
    // Set local oscillator frequency in Hz.
    // int64_t frequency
    SetFreq,
    // Set the CW TX frequency in Hz.
    // int64_t frequency
    SetCWTxFreq,
    // Set the CW keyer speed in Words per Minute.
    // Limited to <5, 45>
    // uint8_t
    SetCWKeyerSpeed,
    // KeyerMode mode
    // uint8_t
    SetKeyerMode,
    // Delay of the dit sent after dit played, to avoid hot switching of the AMP relay, in microseconds. Maximum time is 15ms.
    // Relay hang after the last dit, in microseconds. Maximum time is 10 seconds.
    // bool enabled, uint32_t delay, uint32_t hang
    SetAMPControl,
    // CW phase & amplitude balance and output power.
    // double phase_balance_deg, double amplitude_balance, double power
    SetIQBalanceAndPower,
};

class Cat {
public:
    Cat() {}
    ~Cat() {}
    bool init(libusb_device_handle *handle);

    const std::string get_error() const { return error; }
    std::string error;
    std::string serialNumber;

    // Set local oscillator frequency in Hz.
    bool set_freq(int64_t frequency);
    // Set the CW TX frequency in Hz.
    bool set_cw_tx_freq(int64_t frequency);
    // Set the CW keyer speed in Words per Minute.
    // Limited to <5, 45>
    bool set_cw_keyer_speed(int wpm);
    bool set_cw_keyer_mode(KeyerMode mode);
    // Delay of the dit sent after dit played, to avoid hot switching of the AMP relay, in microseconds. Maximum time is 15ms.
    // Relay hang after the last dit, in microseconds. Maximum time is 10 seconds.
    bool set_amp_control(bool enabled, int delay, int hang);

    bool setIQBalanceAndPower(double phase_balance_deg, double amplitude_balance, double power);

private:
//    int findPeaberryDevice();
    std::string readUsbString(struct usb_dev_handle *udh, uint8_t iDesc);

    libusb_context            *m_libusb_context = nullptr;
    libusb_device            *m_libusb_device  = nullptr;
    libusb_device_handle    *m_libusb_device_handle = nullptr;

    int64_t freqChangeMark = 0;
    int64_t currentFreq = 0;
    int64_t requestedFreq = 0;
    int64_t freq = 0;
    int64_t xit = 0;
    int64_t rit = 0;
    bool    transmitOK = false;

    void start();
    void stop();
    void setFreq(int64_t f) { freq = f; requestedFreq = f + rit; approveTransmit(); }
    void setXit(int64_t f) { xit = f; approveTransmit(); }
    void setRit(int64_t f) { rit = f; requestedFreq = f + freq; }

private:
    void doWork();
    void approveTransmit();
};

extern Cat g_Cat;

#endif // CAT_H
