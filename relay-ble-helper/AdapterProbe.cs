using Windows.Devices.Bluetooth;
using Windows.Foundation.Metadata;

namespace SignalLess.Relay.Ble;

internal sealed record AdapterInfo(BluetoothAdapter Adapter, uint MaxAdvertisementDataLength);

internal static class AdapterProbe
{
    private const string AdapterType = "Windows.Devices.Bluetooth.BluetoothAdapter";
    private const string ServiceProviderType =
        "Windows.Devices.Bluetooth.GenericAttributeProfile.GattServiceProvider";
    private const string AdvertisingParametersType =
        "Windows.Devices.Bluetooth.GenericAttributeProfile.GattServiceProviderAdvertisingParameters";
    private const string WatcherType =
        "Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher";

    public static bool IsServiceDataSupported =>
        ApiInformation.IsTypePresent(AdvertisingParametersType) &&
        ApiInformation.IsPropertyPresent(AdvertisingParametersType, "ServiceData");

    public static bool IsWatcherSupported => ApiInformation.IsTypePresent(WatcherType);

    public static bool IsServiceProviderSupported => ApiInformation.IsTypePresent(ServiceProviderType);

    public static async Task<AdapterInfo> RequireAsync(
        bool requirePeripheralRole,
        bool requireCentralRole)
    {
        if (!ApiInformation.IsTypePresent(AdapterType))
        {
            throw new HelperException("NO_ADAPTER", "BluetoothAdapter API is not present");
        }

        BluetoothAdapter? adapter;
        try
        {
            adapter = await BluetoothAdapter.GetDefaultAsync();
        }
        catch (Exception ex)
        {
            throw new HelperException("NO_ADAPTER", ex.GetType().Name);
        }

        if (adapter is null)
        {
            throw new HelperException("NO_ADAPTER", "no default Bluetooth adapter");
        }

        if (!adapter.IsLowEnergySupported)
        {
            throw new HelperException("LE_UNSUPPORTED", "adapter has no Bluetooth LE support");
        }

        if (requirePeripheralRole && !adapter.IsPeripheralRoleSupported)
        {
            throw new HelperException(
                "PERIPHERAL_UNSUPPORTED",
                "adapter cannot advertise as a BLE peripheral");
        }

        if (requireCentralRole && !adapter.IsCentralRoleSupported)
        {
            throw new HelperException(
                "CENTRAL_UNSUPPORTED",
                "adapter cannot scan as a BLE central");
        }

        var maxAdvertisementDataLength = 0u;
        if (ApiInformation.IsPropertyPresent(AdapterType, "MaxAdvertisementDataLength"))
        {
            maxAdvertisementDataLength = adapter.MaxAdvertisementDataLength;
        }

        return new AdapterInfo(adapter, maxAdvertisementDataLength);
    }
}
