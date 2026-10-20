using System.Security.Cryptography;
using System.Text;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace SignalLess.Relay.Ble;

internal static class GattServerHost
{
    private static readonly Guid ServiceUuid =
        new("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");

    private static readonly Guid CharacteristicUuid =
        new("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D");

    public static async Task<int> RunAsync(HelperOptions options)
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

        using var host = await Server.StartAsync(options);
        Log.Info("ready; press Ctrl+C to stop");

        return await host.WaitForShutdownAsync();
    }

    private sealed class Server : IDisposable
    {
        private readonly GattServiceProvider _provider;
        private readonly GattLocalCharacteristic _characteristic;
        private readonly HelperOptions _options;
        private readonly object _gate = new();
        private readonly Dictionary<string, Link> _links = new(StringComparer.Ordinal);
        private readonly TaskCompletionSource<int> _exit =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly byte[] _readValue = { 0x01 };

        private long _nextLinkId;

        private Server(
            GattServiceProvider provider,
            GattLocalCharacteristic characteristic,
            HelperOptions options)
        {
            _provider = provider;
            _characteristic = characteristic;
            _options = options;

            _provider.AdvertisementStatusChanged += OnAdvertisementStatusChanged;
            _characteristic.ReadRequested += OnReadRequested;
            _characteristic.WriteRequested += OnWriteRequested;
            _characteristic.SubscribedClientsChanged += OnSubscribedClientsChanged;
        }

        public static async Task<Server> StartAsync(HelperOptions options)
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

            var server = new Server(provider, characteristicResult.Characteristic, options);
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

            if (args.Status == GattServiceProviderAdvertisementStatus.StartedWithoutAllAdvertisementData)
            {
                Log.Warn(
                    "ADVERTISING_DEGRADED: service data may have displaced the " +
                    "service UUID; turn the experimental flag off");
            }
            else if (args.Status == GattServiceProviderAdvertisementStatus.Aborted)
            {
                Log.Error($"ADVERTISING_FAILED: {args.Error}");
                _exit.TrySetResult(2);
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
                        pair.Value);

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
                Log.Info($"link down link={link.Id} reason=remote");
            }

            foreach (var link in added)
            {
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

                if (!_options.Echo)
                {
                    return;
                }

                try
                {
                    // Phase 2 spike: echo inline after responding to the write.
                    // Phase 3 moves this to the bounded per-link writer loop.
                    await link.NotifyAsync(_characteristic, value);
                }
                catch (Exception ex)
                {
                    Log.Warn($"tx link={link.Id} failed={ex.GetType().Name}");
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
                Log.Info(
                    $"read link={link?.Id ?? "-"} length={request.Length}");

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
            Link? link = null;
            lock (_gate)
            {
                if (_links.TryGetValue(sessionKey, out link))
                {
                    _links.Remove(sessionKey);
                }
            }

            if (link is not null)
            {
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

            _characteristic.ReadRequested -= OnReadRequested;
            _characteristic.WriteRequested -= OnWriteRequested;
            _characteristic.SubscribedClientsChanged -= OnSubscribedClientsChanged;
            _provider.AdvertisementStatusChanged -= OnAdvertisementStatusChanged;

            try
            {
                _provider.StopAdvertising();
            }
            catch (Exception ex)
            {
                Log.Warn($"stop advertising failed={ex.GetType().Name}");
            }
        }
    }

    private sealed class Link
    {
        private readonly SemaphoreSlim _sendGate = new(1, 1);

        public Link(string id, string sessionKey, GattSubscribedClient client)
        {
            Id = id;
            SessionKey = sessionKey;
            Client = client;
        }

        public string Id { get; }
        public string SessionKey { get; }
        public GattSubscribedClient Client { get; }
        public GattSession Session => Client.Session;
        public int Mtu => Session.MaxPduSize;
        public int MaxNotificationSize => Client.MaxNotificationSize;

        public async Task NotifyAsync(
            GattLocalCharacteristic characteristic,
            byte[] value)
        {
            if (value.Length > MaxNotificationSize)
            {
                Log.Warn(
                    $"tx link={Id} bytes={value.Length} " +
                    $"skipped=maxNotification={MaxNotificationSize}");
                return;
            }

            await _sendGate.WaitAsync();
            try
            {
                var result = await characteristic.NotifyValueAsync(
                    BufferUtil.ToBuffer(value),
                    Client);

                if (result.Status != GattCommunicationStatus.Success)
                {
                    Log.Warn(
                        $"tx link={Id} status={result.Status} " +
                        $"protocolError=0x{result.ProtocolError:X2}");
                    return;
                }

                Log.Info($"tx link={Id} bytes={result.BytesSent} echo=true");
            }
            finally
            {
                _sendGate.Release();
            }
        }
    }
}
