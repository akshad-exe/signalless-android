using System.Text;
using SignalLess.Relay.Ble;

Console.OutputEncoding = Encoding.UTF8;

try
{
    var options = HelperOptions.Parse(args);

    if (options.ShowHelp)
    {
        Console.WriteLine(HelperOptions.HelpText);
        return 0;
    }

    if (options.ShowVersion)
    {
        Console.WriteLine($"relay-ble-helper {HelperInfo.Version}");
        return 0;
    }

    if (options.Scan)
    {
        return await AdvertisementProbe.RunAsync(options.ScanSeconds);
    }

    return await GattServerHost.RunAsync(options);
}
catch (HelperException ex)
{
    Log.Error($"{ex.Code}: {ex.Message}");
    return 1;
}
catch (Exception ex)
{
    // Never print exception messages from WinRT callbacks: they can contain
    // device identifiers. The type name is enough for the terminal harness.
    Log.Error($"UNEXPECTED: {ex.GetType().Name}");
    return 1;
}
