using System;
using System.IO;
using System.Linq;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Threading.Tasks;

namespace Swapy {
    public sealed class ReleaseHashEntry {
        public string path { get; set; }
        public string sha256 { get; set; }
        public long bytes { get; set; }
    }
    public static class ReleaseHashes {
        public static ReleaseHashEntry[] Create(string root) {
            var results = new ConcurrentBag<ReleaseHashEntry>();
            Parallel.ForEach(Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories),
                new ParallelOptions { MaxDegreeOfParallelism = 8 }, file => {
                    using (var algorithm = SHA256.Create())
                    using (var stream = File.OpenRead(file)) {
                        results.Add(new ReleaseHashEntry {
                            path = file.Substring(root.Length + 1).Replace('\\', '/'),
                            sha256 = BitConverter.ToString(algorithm.ComputeHash(stream)).Replace("-", "").ToLowerInvariant(),
                            bytes = stream.Length
                        });
                    }
                });
            return results.OrderBy(entry => entry.path, StringComparer.Ordinal).ToArray();
        }
    }
}
