using Windows.Devices.Bluetooth.Advertisement;

namespace SignalLess.Relay.Ble;

internal static class AdvertisementProbe
{
    private static readonly Guid ServiceUuid =
        new("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");

    public static async Task<int> RunAsync(int seconds)
    {
        if (!AdapterProbe.IsWatcherSupported)
        {
            throw new HelperException(
                "SCAN_FAILED",
                "BluetoothLEAdvertisementWatcher API is not present");
        }

        await AdapterProbe.RequireAsync(
            requirePeripheralRole: false,
            requireCentralRole: true);

        var matches = 0;
        var watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };

        watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(ServiceUuid);
        watcher.Received += (_, args) =>
        {
            matches++;
            foreach (var section in args.Advertisement.DataSections)
            {
                // Print only the service UUID list and service data sections.
                // Names, addresses, and unrelated payloads stay out of the log.
                if (section.DataType is not (0x07 or 0x21))
                {
                    continue;
                }

                var bytes = BufferUtil.ReadBytes(section.Data);
                Log.Info(
                    $"ad type=0x{section.DataType:X2} bytes={bytes.Length} " +
                    $"hex={Convert.ToHexString(bytes).ToLowerInvariant()}");
            }
        };
        watcher.Stopped += (_, args) =>
        {
            Log.Info($"scan stopped reason={args.Error}");
        };

        Log.Info($"scan started seconds={seconds} service={ServiceUuid}");
        try
        {
            watcher.Start();
        }
        catch (Exception ex)
        {
            throw new HelperException("SCAN_FAILED", ex.GetType().Name);
        }

        try
        {
            await Task.Delay(TimeSpan.FromSeconds(seconds));
        }
        finally
        {
            try
            {
                watcher.Stop();
            }
            catch (Exception ex)
            {
                Log.Warn($"scan stop failed={ex.GetType().Name}");
            }
        }

        Log.Info($"scan finished matches={matches}");
        return 0;
    }
}
