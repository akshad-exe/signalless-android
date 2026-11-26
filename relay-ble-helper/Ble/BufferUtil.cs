using Windows.Storage.Streams;

namespace SignalLess.Relay.Ble;

internal static class BufferUtil
{
    public static byte[] ReadBytes(IBuffer buffer)
    {
        var length = checked((int)buffer.Length);
        var bytes = new byte[length];
        if (length == 0)
        {
            return bytes;
        }

        using var reader = DataReader.FromBuffer(buffer);
        reader.ReadBytes(bytes);
        return bytes;
    }

    public static IBuffer ToBuffer(byte[] bytes)
    {
        using var writer = new DataWriter();
        writer.WriteBytes(bytes);
        return writer.DetachBuffer();
    }
}
