using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Channels;
using SignalLess.Relay.Cli;
using SignalLess.Relay.Diagnostics;
using SignalLess.Relay.Ipc;

namespace SignalLess.Relay.Ble;

internal static class GattServerHost
{
    private static readonly Guid ServiceUuid =
        new("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");

    private static readonly Guid CharacteristicUuid =
        new("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D");

    public static async Task<int> RunAsync(HelperOptions options, IHelperOutput output)
    {
        if (!AdapterProbe.IsServiceProviderSupported)
        {
            throw new HelperException(
                "GATT_CREATE_FAILED",
                "GattServiceProvider API is not present");
        }

        var adapterInfo = await AdapterProbe.RequireAsync(
            requirePeripheralRole: true,
            requireCentralRole: false);

        Log.Info(
            $"adapter le=true peripheral=true " +
            $"maxAdvertisementDataLength={adapterInfo.MaxAdvertisementDataLength}");

        if (options.ServiceData is not null && !AdapterProbe.IsServiceDataSupported)
        {
            throw new HelperException(
                "GATT_CREATE_FAILED",
                "ServiceData advertising is not supported by this Windows build");
        }

        using var host = await Server.StartAsync(options, output);
        output.Ready(
            options.MaxLinks,
            AdapterProbe.IsServiceDataSupported,
            options.ServiceData is not null,
            Environment.OSVersion.Version.ToString());
        Log.Info(options.Ipc
            ? "ready for NDJSON commands"
            : "ready; press Ctrl+C to stop");

        if (options.Ipc)
        {
            IpcLoop.Start(host, output);
        }

        return await host.WaitForShutdownAsync();
    }

    internal sealed class Server : IDisposable
    {
        private readonly GattServiceProvider _provider;
        private readonly GattLocalCharacteristic _characteristic;
        private readonly HelperOptions _options;
        private readonly IHelperOutput _output;
        private readonly object _gate = new();
        private readonly Dictionary<string, Link> _links = new(StringComparer.Ordinal);
        private readonly TaskCompletionSource<int> _exit =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly byte[] _readValue = { 0x01 };

        private long _nextLinkId;

        private Server(
            GattServiceProvider provider,
            GattLocalCharacteristic characteristic,
            HelperOptions options,
            IHelperOutput output)
        {
            _provider = provider;
            _characteristic = characteristic;
            _options = options;
            _output = output;

            _provider.AdvertisementStatusChanged += OnAdvertisementStatusChanged;
            _characteristic.ReadRequested += OnReadRequested;
            _characteristic.WriteRequested += OnWriteRequested;
            _characteristic.SubscribedClientsChanged += OnSubscribedClientsChanged;
        }

        public static async Task<Server> StartAsync(
            HelperOptions options,
            IHelperOutput output)
        {
            GattServiceProviderResult providerResult;
            try
            {
                providerResult = await GattServiceProvider.CreateAsync(ServiceUuid);
            }
            catch (Exception ex)
            {
                throw new HelperException("GATT_CREATE_FAILED", ex.GetType().Name);
            }

            if (providerResult.Error != BluetoothError.Success ||
                providerResult.ServiceProvider is null)
            {
                throw new HelperException(
                    "GATT_CREATE_FAILED",
                    providerResult.Error.ToString());
            }

            var provider = providerResult.ServiceProvider;
            var characteristicParameters = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties =
                    GattCharacteristicProperties.Read |
                    GattCharacteristicProperties.Write |
                    GattCharacteristicProperties.WriteWithoutResponse |
                    GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.Plain,
                WriteProtectionLevel = GattProtectionLevel.Plain,
                UserDescription = "SignalLess relay",
            };

            GattLocalCharacteristicResult characteristicResult;
            try
            {
                characteristicResult = await provider.Service.CreateCharacteristicAsync(
                    CharacteristicUuid,
                    characteristicParameters);
            }
            catch (Exception ex)
            {
                throw new HelperException("GATT_CREATE_FAILED", ex.GetType().Name);
            }

            if (characteristicResult.Error != BluetoothError.Success ||
                characteristicResult.Characteristic is null)
            {
                throw new HelperException(
                    "GATT_CREATE_FAILED",
                    characteristicResult.Error.ToString());
            }

            var server = new Server(
                provider,
                characteristicResult.Characteristic,
                options,
                output);
            try
            {
                server.StartAdvertising();
            }
            catch
            {
                server.Dispose();
                throw;
            }

            return server;
        }

        private void StartAdvertising()
        {
            var hasServiceData = _options.ServiceData is not null;
            var parameters = new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = _options.Discoverable,
            };

            if (_options.ServiceData is { Length: > 0 } serviceData)
            {
                parameters.ServiceData = BufferUtil.ToBuffer(serviceData);
            }

            try
            {
                _provider.StartAdvertising(parameters);
            }
            catch (Exception ex)
            {
                throw new HelperException("ADVERTISING_FAILED", ex.GetType().Name);
            }

            var status = _provider.AdvertisementStatus;
            Log.Info(
                $"advertising discoverable={_options.Discoverable.ToString().ToLowerInvariant()} " +
                $"connectable=true serviceData={hasServiceData.ToString().ToLowerInvariant()} " +
                $"status={status}");

            _output.Advertising(status.ToString().ToLowerInvariant(), hasServiceData, null);

            if (status == GattServiceProviderAdvertisementStatus.Aborted)
            {
                throw new HelperException("ADVERTISING_FAILED", "adapter returned Aborted");
            }

            if (status == GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData)
            {
                Log.Warn(
                    "ADVERTISING_DEGRADED: the OS accepted the request but dropped " +
                    "advertisement data; verify the service UUID on the phone");
            }
        }

        private void OnAdvertisementStatusChanged(
            GattServiceProvider sender,
            GattServiceProviderAdvertisementStatusChangedEventArgs args)
        {
            Log.Info($"advertising state={args.Status} error={args.Error}");

            if (args.Status == GattServiceProviderAdvertisementStatus.Started)
            {
                _output.Advertising("started", _options.ServiceData is not null, args.Error.ToString());
            }
            else if (args.Status == GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData)
            {
                Log.Warn(
                    "ADVERTISING_DEGRADED: service data may have displaced the " +
                    "service UUID; turn the experimental flag off");
                _output.Advertising("degraded", _options.ServiceData is not null, args.Error.ToString());
            }
            else if (args.Status == GattServiceProviderAdvertisementStatus.Aborted)
            {
                Log.Error($"ADVERTISING_FAILED: {args.Error}");
                _output.Advertising("aborted", _options.ServiceData is not null, args.Error.ToString());
                _exit.TrySetResult(2);
            }
            else
            {
                _output.Advertising("stopped", _options.ServiceData is not null, args.Error.ToString());
            }
        }

        private void OnSubscribedClientsChanged(GattLocalCharacteristic sender, object args)
        {
            var added = new List<Link>();
            var removed = new List<Link>();

            lock (_gate)
            {
                var current = new Dictionary<string, GattSubscribedClient>(StringComparer.Ordinal);
                foreach (var client in sender.SubscribedClients)
                {
                    current[GetSessionKey(client.Session)] = client;
                }

                foreach (var pair in _links.ToList())
                {
                    if (current.ContainsKey(pair.Key))
                    {
                        continue;
                    }

                    _links.Remove(pair.Key);
                    removed.Add(pair.Value);
                }

                foreach (var pair in current)
                {
                    if (_links.ContainsKey(pair.Key))
                    {
                        continue;
                    }

                    if (_links.Count >= _options.MaxLinks)
                    {
                        Log.Warn(
                            $"LINK_LIMIT: dropping new subscription at " +
                            $"maxLinks={_options.MaxLinks}");
                        continue;
                    }

                    var link = new Link(
                        $"l{Interlocked.Increment(ref _nextLinkId)}",
                        pair.Key,
                        pair.Value,
                        _characteristic,
                        _options.Echo && !_options.Ipc);

                    _links[pair.Key] = link;
                    link.Session.SessionStatusChanged += (_, eventArgs) =>
                    {
                        if (eventArgs.Status == GattSessionStatus.Closed)
                        {
                            RemoveLink(link.SessionKey, "session_closed");
                        }
                    };
                    added.Add(link);
                }
            }

            foreach (var link in removed)
            {
                link.Dispose();
                _output.LinkDown(link.Id, "remote");
            }

            foreach (var link in added)
            {
                _output.LinkUp(link.Id, link.Mtu, link.MaxNotificationSize);
                Log.Info(
                    $"link up link={link.Id} mtu={link.Mtu} " +
                    $"maxNotification={link.MaxNotificationSize}");
            }
        }

        private async void OnWriteRequested(
            GattLocalCharacteristic sender,
            GattWriteRequestedEventArgs args)
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                if (request is null)
                {
                    return;
                }

                var value = BufferUtil.ReadBytes(request.Value);
                var link = FindLink(args.Session);
                if (link is null)
                {
                    Log.Warn($"rx unlinked bytes={value.Length}");
                    if (request.Option == GattWriteOption.WriteWithResponse)
                    {
                        request.RespondWithProtocolError(0x11);
                    }

                    return;
                }

                Log.Info($"rx link={link.Id} bytes={value.Length}");
                if (request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.Respond();
                }

                _output.Rx(link.Id, value);

                if (_options.Echo && !_options.Ipc)
                {
                    if (!link.TryEnqueue(value, 0, out _))
                    {
                        Log.Warn($"echo link={link.Id} dropped=queue_full");
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Warn($"write failed={ex.GetType().Name}");
            }
            finally
            {
                deferral.Complete();
            }
        }

        private async void OnReadRequested(
            GattLocalCharacteristic sender,
            GattReadRequestedEventArgs args)
        {
            var deferral = args.GetDeferral();
            try
            {
                var request = await args.GetRequestAsync();
                if (request is null)
                {
                    return;
                }

                var link = FindLink(args.Session);
                Log.Info($"read link={link?.Id ?? "-"} length={request.Length}");

                request.RespondWithValue(BufferUtil.ToBuffer(_readValue));
            }
            catch (Exception ex)
            {
                Log.Warn($"read failed={ex.GetType().Name}");
            }
            finally
            {
                deferral.Complete();
            }
        }

        public async Task<(bool Ok, string? Error)> SendAsync(
            string linkId,
            long sequence,
            byte[] data)
        {
            Link? link;
            lock (_gate)
            {
                link = _links.Values.FirstOrDefault(candidate => candidate.Id == linkId);
            }

            if (link is null)
            {
                return (false, "unknown link");
            }

            if (data.Length > link.MaxNotificationSize)
            {
                return (false, "oversized notification");
            }

            return await link.SendAsync(data, sequence);
        }

        public void StopAdvertising()
        {
            try
            {
                _provider.StopAdvertising();
            }
            catch (Exception ex)
            {
                Log.Warn($"stop advertising failed={ex.GetType().Name}");
            }
        }

        public void RequestShutdown(int exitCode) => _exit.TrySetResult(exitCode);

        private Link? FindLink(GattSession session)
        {
            var key = GetSessionKey(session);
            lock (_gate)
            {
                return _links.TryGetValue(key, out var link) ? link : null;
            }
        }

        private void RemoveLink(string sessionKey, string reason)
        {
            Link? link;
            lock (_gate)
            {
                _links.TryGetValue(sessionKey, out link);
                if (link is not null)
                {
                    _links.Remove(sessionKey);
                }
            }

            if (link is not null)
            {
                link.Dispose();
                _output.LinkDown(link.Id, reason);
                Log.Info($"link down link={link.Id} reason={reason}");
            }
        }

        private static string GetSessionKey(GattSession session)
        {
            var deviceId = session.DeviceId?.Id ?? string.Empty;
            var digest = SHA256.HashData(Encoding.UTF8.GetBytes(deviceId));
            return Convert.ToHexString(digest);
        }

        public async Task<int> WaitForShutdownAsync()
        {
            ConsoleCancelEventHandler handler = (_, eventArgs) =>
            {
                eventArgs.Cancel = true;
                _exit.TrySetResult(0);
            };

            Console.CancelKeyPress += handler;
            try
            {
                return await _exit.Task;
            }
            finally
            {
                Console.CancelKeyPress -= handler;
            }
        }

        public void Dispose()
        {
            _exit.TrySetResult(0);
            StopAdvertising();

            _characteristic.ReadRequested -= OnReadRequested;
            _characteristic.WriteRequested -= OnWriteRequested;
            _characteristic.SubscribedClientsChanged -= OnSubscribedClientsChanged;
            _provider.AdvertisementStatusChanged -= OnAdvertisementStatusChanged;

            List<Link> links;
            lock (_gate)
            {
                links = _links.Values.ToList();
                _links.Clear();
            }
            links.ForEach(link => link.Dispose());
        }
    }

    private sealed class Link : IDisposable
    {
        private readonly SemaphoreSlim _sendGate = new(1, 1);
        private readonly GattLocalCharacteristic _characteristic;
        private readonly Channel<SendItem> _queue;
        private readonly CancellationTokenSource _lifetime = new();
        private readonly Task _writer;
        private readonly bool _echo;

        public Link(
            string id,
            string sessionKey,
            GattSubscribedClient client,
            GattLocalCharacteristic characteristic,
            bool echo)
        {
            Id = id;
            SessionKey = sessionKey;
            Client = client;
            _characteristic = characteristic;
            _echo = echo;
            _queue = Channel.CreateBounded<SendItem>(
                new BoundedChannelOptions(64)
                {
                    SingleReader = true,
                    SingleWriter = false,
                    FullMode = BoundedChannelFullMode.Wait,
                });
            _writer = Task.Run(WriterLoopAsync);
        }

        public string Id { get; }
        public string SessionKey { get; }
        public GattSubscribedClient Client { get; }
        public GattSession Session => Client.Session;
        public int Mtu => Session.MaxPduSize;
        public int MaxNotificationSize => Client.MaxNotificationSize;

        public bool TryEnqueue(
            byte[] value,
            long sequence,
            out Task<(bool Ok, string? Error)>? completion)
        {
            var result = new TaskCompletionSource<(bool Ok, string? Error)>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            if (!_queue.Writer.TryWrite(new SendItem(value, sequence, result)))
            {
                completion = null;
                return false;
            }

            completion = result.Task;
            return true;
        }

        public async Task<(bool Ok, string? Error)> SendAsync(
            byte[] value,
            long sequence)
        {
            if (!TryEnqueue(value, sequence, out var completion) || completion is null)
            {
                return (false, "TX_QUEUE_FULL");
            }

            return await completion;
        }

        private async Task WriterLoopAsync()
        {
            try
            {
                await foreach (var item in _queue.Reader.ReadAllAsync(_lifetime.Token))
                {
                    var result = await NotifyAsync(item.Data);
                    item.Completion.TrySetResult(result);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                Log.Warn($"tx queue link={Id} failed={ex.GetType().Name}");
            }
            finally
            {
                while (_queue.Reader.TryRead(out var pending))
                {
                    pending.Completion.TrySetResult((false, "link_down"));
                }
            }
        }

        private async Task<(bool Ok, string? Error)> NotifyAsync(byte[] value)
        {
            if (value.Length > MaxNotificationSize)
            {
                Log.Warn(
                    $"tx link={Id} bytes={value.Length} " +
                    $"skipped=maxNotification={MaxNotificationSize}");
                return (false, "oversized notification");
            }

            await _sendGate.WaitAsync();
            try
            {
                var result = await _characteristic.NotifyValueAsync(
                    BufferUtil.ToBuffer(value),
                    Client);

                if (result.Status != GattCommunicationStatus.Success)
                {
                    Log.Warn(
                        $"tx link={Id} status={result.Status} " +
                        $"protocolError=0x{result.ProtocolError:X2}");
                    return (false, result.Status.ToString());
                }

                Log.Info(
                    $"tx link={Id} bytes={result.BytesSent} echo=" +
                    _echo.ToString().ToLowerInvariant());
                return (true, null);
            }
            catch (Exception ex)
            {
                Log.Warn($"tx link={Id} failed={ex.GetType().Name}");
                return (false, ex.GetType().Name);
            }
            finally
            {
                _sendGate.Release();
            }
        }

        public void Dispose()
        {
            _lifetime.Cancel();
            _queue.Writer.TryComplete();
        }

        private sealed record SendItem(
            byte[] Data,
            long Sequence,
            TaskCompletionSource<(bool Ok, string? Error)> Completion);
    }
}
