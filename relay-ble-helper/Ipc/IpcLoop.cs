using System.Text.Json;
using SignalLess.Relay.Ble;

namespace SignalLess.Relay.Ipc;

/// <summary>
/// Reads NDJSON commands from stdin and drives the GATT server.
/// Standard output is owned by IpcOutput; this loop never writes to it.
/// </summary>
internal static class IpcLoop
{
    private const int MaxLineBytes = 1_048_576;

    public static void Start(GattServerHost.Server server, IHelperOutput output)
    {
        _ = Task.Run(() => RunAsync(server, output));
    }

    private static async Task RunAsync(GattServerHost.Server server, IHelperOutput output)
    {
        try
        {
            string? line;
            while ((line = await Console.In.ReadLineAsync()) is not null)
            {
                if (string.IsNullOrWhiteSpace(line))
                {
                    continue;
                }

                if (System.Text.Encoding.UTF8.GetByteCount(line) > MaxLineBytes)
                {
                    output.Error("PROTOCOL_ERROR", "command line is too long");
                    continue;
                }

                try
                {
                    using var document = JsonDocument.Parse(line);
                    var root = document.RootElement;
                    if (!root.TryGetProperty("v", out var version) || version.GetInt32() != 1)
                    {
                        output.Error("PROTOCOL_ERROR", "unsupported protocol version");
                        continue;
                    }

                    var type = root.TryGetProperty("type", out var typeElement)
                        ? typeElement.GetString()
                        : null;

                    switch (type)
                    {
                        case "start":
                            break;

                        case "tx":
                            await HandleTransmitAsync(server, output, root);
                            break;

                        case "stop":
                            server.StopAdvertising();
                            break;

                        case "shutdown":
                            server.RequestShutdown(0);
                            return;

                        default:
                            output.Error("PROTOCOL_ERROR", "unknown command");
                            break;
                    }
                }
                catch (JsonException)
                {
                    output.Error("PROTOCOL_ERROR", "invalid JSON command");
                }
                catch (FormatException)
                {
                    output.Error("PROTOCOL_ERROR", "invalid base64 data");
                }
                catch (InvalidOperationException)
                {
                    output.Error("PROTOCOL_ERROR", "command field has the wrong type");
                }
                catch (Exception ex)
                {
                    output.Error("PROTOCOL_ERROR", ex.GetType().Name);
                }
            }

            server.RequestShutdown(0);
        }
        catch (Exception ex)
        {
            output.Error("PROTOCOL_ERROR", ex.GetType().Name);
            server.RequestShutdown(1);
        }
    }

    private static async Task HandleTransmitAsync(
        GattServerHost.Server server,
        IHelperOutput output,
        JsonElement root)
    {
        if (!root.TryGetProperty("link", out var linkElement) ||
            linkElement.GetString() is not { Length: > 0 } link)
        {
            output.Error("PROTOCOL_ERROR", "tx requires a link");
            return;
        }

        if (!root.TryGetProperty("seq", out var seqElement) ||
            !seqElement.TryGetInt64(out var sequence))
        {
            output.Error("PROTOCOL_ERROR", "tx requires a numeric seq");
            return;
        }

        if (!root.TryGetProperty("data", out var dataElement) ||
            dataElement.GetString() is not { } encoded)
        {
            output.Error("PROTOCOL_ERROR", "tx requires data");
            return;
        }

        var data = Convert.FromBase64String(encoded);
        var result = await server.SendAsync(link, sequence, data);
        output.TxResult(link, sequence, result.Ok, result.Error);
    }
}
