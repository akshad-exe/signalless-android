using System.Globalization;

namespace SignalLess.Relay.Cli;

internal sealed record HelperOptions
{
    public bool ShowHelp { get; init; }
    public bool ShowVersion { get; init; }
    public bool Scan { get; init; }
    public bool Ipc { get; init; }
    public int ScanSeconds { get; init; } = 15;
    public bool Discoverable { get; init; } = true;
    public bool Echo { get; init; } = true;
    public int MaxLinks { get; init; } = 7;
    public byte[]? ServiceData { get; init; }

    public static HelperOptions Parse(string[] args)
    {
        var options = new HelperOptions();

        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--help" or "-h":
                    options = options with { ShowHelp = true };
                    break;
                case "--version":
                    options = options with { ShowVersion = true };
                    break;
                case "--scan":
                    options = options with { Scan = true };
                    break;
                case "--ipc":
                    options = options with { Ipc = true };
                    break;
                case "--scan-seconds":
                    options = options with
                    {
                        ScanSeconds = ParseInt(args, ref i, "--scan-seconds", 1, 300),
                    };
                    break;
                case "--no-discoverable":
                    options = options with { Discoverable = false };
                    break;
                case "--discoverable":
                    options = options with { Discoverable = true };
                    break;
                case "--no-echo":
                    options = options with { Echo = false };
                    break;
                case "--max-links":
                    options = options with
                    {
                        MaxLinks = ParseInt(args, ref i, "--max-links", 1, 7),
                    };
                    break;
                case "--service-data":
                    options = options with
                    {
                        ServiceData = ParseServiceData(args, ref i),
                    };
                    break;
                default:
                    throw new HelperException(
                        "PROTOCOL_ERROR",
                        $"unknown argument: {args[i]}");
            }
        }

        return options;
    }

    private static int ParseInt(string[] args, ref int index, string name, int min, int max)
    {
        if (++index >= args.Length)
        {
            throw new HelperException("PROTOCOL_ERROR", $"{name} requires a value");
        }

        if (!int.TryParse(args[index], NumberStyles.None, CultureInfo.InvariantCulture, out var value) ||
            value < min || value > max)
        {
            throw new HelperException(
                "PROTOCOL_ERROR",
                $"{name} must be an integer from {min} to {max}");
        }

        return value;
    }

    private static byte[] ParseServiceData(string[] args, ref int index)
    {
        if (++index >= args.Length)
        {
            throw new HelperException(
                "PROTOCOL_ERROR",
                "--service-data requires 1 to 8 bytes of hex");
        }

        byte[] bytes;
        try
        {
            bytes = Convert.FromHexString(args[index]);
        }
        catch (FormatException)
        {
            throw new HelperException(
                "PROTOCOL_ERROR",
                "--service-data must be an even-length hex string");
        }

        if (bytes.Length is < 1 or > 8)
        {
            throw new HelperException(
                "PROTOCOL_ERROR",
                "--service-data must be 1 to 8 bytes");
        }

        return bytes;
    }

    public const string HelpText = """
        SignalLess relay BLE helper

        Usage:
          relay-ble-helper [options]
          relay-ble-helper --scan [--scan-seconds N]
          relay-ble-helper --ipc [options]

        Modes:
          (default)             Advertise the GATT service and echo writes.
          --scan                Print matching advertised service sections.
          --ipc                 Speak the NDJSON process protocol on stdout.

        Options:
          --service-data HEX    Experimental 1-8 byte service data. Default off.
          --no-discoverable     Omit the friendly name from advertising.
          --no-echo             Do not notify a received write back to its link.
          --max-links N         Local link cap, 1-7. Default 7.
          --scan-seconds N      Scan duration, 1-300 seconds. Default 15.
          --help, -h            Show this help.
          --version             Show the helper version.

        In IPC mode, stdout is NDJSON only and diagnostics go to stderr. The
        helper exits when stdin closes or after a shutdown command. No MAC
        addresses, device names, payloads, or keys are logged.
        """;
}

internal static class HelperInfo
{
    public const string Version = "0.2.0";
}

internal sealed class HelperException : Exception
{
    public HelperException(string code, string message) : base(message)
    {
        Code = code;
    }

    public string Code { get; }
}
