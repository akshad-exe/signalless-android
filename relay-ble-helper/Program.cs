using System.Text;
using SignalLess.Relay.Advertising;
using SignalLess.Relay.Ble;
using SignalLess.Relay.Cli;
using SignalLess.Relay.Diagnostics;
using SignalLess.Relay.Ipc;

// NDJSON is UTF-8 without a BOM. The default Encoding.UTF8 instance emits a
// preamble on a new stream, which would corrupt the first protocol line.
Console.OutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

IHelperOutput? output = null;

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

    output = options.Ipc ? new IpcOutput() : new TerminalOutput();
    return await GattServerHost.RunAsync(options, output);
}
catch (HelperException ex)
{
    output?.Error(ex.Code, ex.Message);
    if (output is null)
    {
        Log.Error($"{ex.Code}: {ex.Message}");
    }
    return 1;
}
catch (Exception ex)
{
    // Never print exception messages from WinRT callbacks: they can contain
    // device identifiers. The type name is enough for the terminal harness.
    output?.Error("HELPER_EXIT", ex.GetType().Name);
    if (output is null)
    {
        Log.Error($"UNEXPECTED: {ex.GetType().Name}");
    }
    return 1;
}
