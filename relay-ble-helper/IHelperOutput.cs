using System.Text.Json;
using System.Text.Json.Serialization;

namespace SignalLess.Relay.Ble;

/// <summary>
/// Sink for helper lifecycle events. The terminal implementation leaves the
/// existing human-readable stderr diagnostics in place; the IPC implementation
/// writes NDJSON to stdout and reserves stderr for diagnostics.
/// </summary>
internal interface IHelperOutput
{
    void Ready(
        int maxLinks,
        bool serviceDataSupported,
        bool serviceDataEnabled,
        string api);

    void Advertising(string state, bool serviceData, string? detail);

    void LinkUp(string link, int mtu, int maxNotification);

    void LinkDown(string link, string reason);

    void Rx(string link, byte[] data);

    void TxResult(string link, long sequence, bool ok, string? error);

    void Error(string code, string message);
}

internal sealed class TerminalOutput : IHelperOutput
{
    public void Ready(
        int maxLinks,
        bool serviceDataSupported,
        bool serviceDataEnabled,
        string api)
    {
    }

    public void Advertising(string state, bool serviceData, string? detail)
    {
    }

    public void LinkUp(string link, int mtu, int maxNotification)
    {
    }

    public void LinkDown(string link, string reason)
    {
    }

    public void Rx(string link, byte[] data)
    {
    }

    public void TxResult(string link, long sequence, bool ok, string? error)
    {
    }

    public void Error(string code, string message) => Log.Error($"{code}: {message}");
}

internal sealed class IpcOutput : IHelperOutput
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly object _gate = new();

    public void Ready(
        int maxLinks,
        bool serviceDataSupported,
        bool serviceDataEnabled,
        string api) =>
        Write(new
        {
            v = 1,
            type = "ready",
            platform = "windows",
            maxLinks,
            serviceDataSupported,
            serviceDataEnabled,
            api,
        });

    public void Advertising(string state, bool serviceData, string? detail) =>
        Write(new { v = 1, type = "advertising", state, serviceData, detail });

    public void LinkUp(string link, int mtu, int maxNotification) =>
        Write(new { v = 1, type = "link_up", link, mtu, maxNotification });

    public void LinkDown(string link, string reason) =>
        Write(new { v = 1, type = "link_down", link, reason });

    public void Rx(string link, byte[] data) =>
        Write(new { v = 1, type = "rx", link, data = Convert.ToBase64String(data) });

    public void TxResult(string link, long sequence, bool ok, string? error) =>
        Write(new { v = 1, type = "tx_result", link, seq = sequence, ok, error });

    public void Error(string code, string message)
    {
        Write(new { v = 1, type = "error", code, message });
        Log.Error($"{code}: {message}");
    }

    private void Write(object message)
    {
        var line = JsonSerializer.Serialize(message, JsonOptions);
        lock (_gate)
        {
            Console.Out.WriteLine(line);
            Console.Out.Flush();
        }
    }
}
