#include "symbian/e32/e32.h"
#include "symbian/sis/sis.h"

#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <string_view>
#include <set>
#include <vector>

namespace {

std::string ReadFile(const std::string& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) throw std::runtime_error("cannot read: " + path);
  std::ostringstream output;
  output << input.rdbuf();
  if (!input.good() && !input.eof()) {
    throw std::runtime_error("failed reading: " + path);
  }
  return output.str();
}

void WriteFile(const std::string& path, std::string_view bytes) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) throw std::runtime_error("cannot write: " + path);
  output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
  if (!output) throw std::runtime_error("failed writing: " + path);
}

struct ParsedArguments {
  std::map<std::string, std::string> values;
  std::vector<std::pair<std::string, std::string>> data_files;
};

ParsedArguments Arguments(int argc, char** argv) {
  if ((argc - 1) % 2 != 0) {
    throw std::runtime_error("arguments must be --name value pairs");
  }
  ParsedArguments parsed;
  std::string pending_data_file;
  std::string pending_data_target;
  bool has_pending_data_file = false;
  bool has_pending_data_target = false;
  for (int index = 1; index < argc; index += 2) {
    const std::string key(argv[index]);
    const std::string value(argv[index + 1]);
    if (!key.starts_with("--")) {
      throw std::runtime_error("invalid argument: " + key);
    }
    if (key == "--data-file") {
      if (has_pending_data_file || value.empty()) {
        throw std::runtime_error("invalid or unmatched --data-file");
      }
      pending_data_file = value;
      has_pending_data_file = true;
    } else if (key == "--data-target") {
      if (has_pending_data_target || value.empty()) {
        throw std::runtime_error("invalid or unmatched --data-target");
      }
      pending_data_target = value;
      has_pending_data_target = true;
    } else if (!parsed.values.emplace(key, value).second) {
      throw std::runtime_error("invalid or duplicate argument: " + key);
    }
    if (has_pending_data_file && has_pending_data_target) {
      parsed.data_files.emplace_back(pending_data_file, pending_data_target);
      pending_data_file.clear();
      pending_data_target.clear();
      has_pending_data_file = false;
      has_pending_data_target = false;
    }
  }
  if (has_pending_data_file || has_pending_data_target) {
    throw std::runtime_error("each --data-file requires a --data-target");
  }
  const std::map<std::string, bool> supported = {
      {"--exe", true}, {"--registration", true},
      {"--registration-target", true}, {"--caption", true},
      {"--caption-target", true}, {"--package-uid", true},
      {"--package-name", true}, {"--vendor", true},
      {"--executable-name", true}, {"--version", true},
      {"--certificate", true}, {"--private-key", true},
      {"--unsigned-output", true}, {"--output", true}};
  for (const auto& [key, value] : parsed.values) {
    (void)value;
    if (!supported.contains(key)) {
      throw std::runtime_error("unsupported argument: " + key);
    }
  }
  return parsed;
}

const std::string& Required(const ParsedArguments& args,
                            const std::string& key) {
  const auto found = args.values.find(key);
  if (found == args.values.end() || found->second.empty()) {
    throw std::runtime_error("missing argument: " + key);
  }
  return found->second;
}

bool Has(const ParsedArguments& args, const std::string& key) {
  return args.values.contains(key);
}

std::array<int32_t, 3> ParseVersion(std::string_view value) {
  std::array<int32_t, 3> result{};
  size_t start = 0;
  for (size_t index = 0; index != result.size(); ++index) {
    const size_t end = index + 1 == result.size() ? value.size() : value.find('.', start);
    if (end == std::string_view::npos || end == start) {
      throw std::runtime_error("version must be MAJOR.MINOR.BUILD");
    }
    int number = 0;
    for (size_t i = start; i < end; ++i) {
      if (value[i] < '0' || value[i] > '9') {
        throw std::runtime_error("version must contain decimal components");
      }
      number = number * 10 + (value[i] - '0');
    }
    result[index] = number;
    start = end + 1;
  }
  if (start != value.size() + 1) {
    throw std::runtime_error("version must have exactly three components");
  }
  return result;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc == 4 && std::string_view(argv[1]) == "--extract-exe") {
      const std::string package = ReadFile(argv[2]);
      auto info = symbian::sis::InspectPackage(package);
      if (!info.ok() || info->executable_payload.empty()) {
        throw std::runtime_error("SIS executable extraction failed");
      }
      WriteFile(argv[3], info->executable_payload);
      return 0;
    }
    if (argc == 4 && std::string_view(argv[1]) == "--extract-files") {
      const std::string package = ReadFile(argv[2]);
      auto info = symbian::sis::InspectPackage(package);
      if (!info.ok() || info->files.empty()) {
        throw std::runtime_error("SIS file extraction failed: " +
                                 (info.ok() ? std::string("empty package")
                                            : info.status().ToString()));
      }
      const std::filesystem::path output_dir(argv[3]);
      if (std::filesystem::exists(output_dir) &&
          !std::filesystem::is_empty(output_dir)) {
        throw std::runtime_error("SIS extraction output must be absent or empty");
      }
      std::filesystem::create_directories(output_dir);
      std::set<std::string> filenames;
      for (const auto& file : info->files) {
        const size_t separator = file.target.find_last_of("\\/");
        const std::string filename = file.target.substr(
            separator == std::string::npos ? 0 : separator + 1);
        if (filename.empty() || filename == "." || filename == ".." ||
            !filenames.insert(filename).second) {
          throw std::runtime_error("Unsafe or duplicate SIS output filename");
        }
        WriteFile((output_dir / filename).string(), file.payload);
      }
      std::cout << "extracted_files=" << filenames.size() << "\n";
      return 0;
    }
    const auto args = Arguments(argc, argv);
    const std::string executable = ReadFile(Required(args, "--exe"));
    const std::string registration = ReadFile(Required(args, "--registration"));
    const std::string caption = ReadFile(Required(args, "--caption"));
    auto executable_info = symbian::e32::InspectImage(executable);
    if (!executable_info.ok()) {
      throw std::runtime_error("E32 executable inspection failed: " +
                               executable_info.status().ToString());
    }
    if (executable_info->dll) {
      throw std::runtime_error("E32 payload is a DLL, not an executable");
    }
    std::ostringstream all_imports;
    std::ostringstream mda_imports;
    std::ostringstream midi_imports;
    bool first_import = true;
    bool first_mda_import = true;
    bool first_midi_import = true;
    for (const auto& block : executable_info->imports) {
      for (const auto& slot : block.slots) {
        if (!first_import) all_imports << ',';
        all_imports << block.dll << ':' << slot.ordinal;
        first_import = false;
        if (block.dll == "mediaclientaudio.dll") {
          if (!first_mda_import) mda_imports << ',';
          mda_imports << slot.ordinal;
          first_mda_import = false;
        }
        if (block.dll.find("midi") != std::string::npos) {
          if (!first_midi_import) midi_imports << ',';
          midi_imports << block.dll << ':' << slot.ordinal;
          first_midi_import = false;
        }
      }
    }

    symbian::sis::PackageOptions options;
    options.uid = static_cast<uint32_t>(std::stoul(Required(args, "--package-uid"),
                                                   nullptr, 0));
    options.name = Required(args, "--package-name");
    options.vendor = Required(args, "--vendor");
    options.executable_name = Required(args, "--executable-name");
    options.version = ParseVersion(Required(args, "--version"));

    std::vector<symbian::sis::ApplicationFile> assets = {
        {Required(args, "--registration-target"), registration},
        {Required(args, "--caption-target"), caption},
    };
    std::vector<symbian::sis::ApplicationFile> data_files;
    std::set<std::string> data_targets;
    for (const auto& [path, target] : args.data_files) {
      if (!data_targets.insert(target).second) {
        throw std::runtime_error("duplicate data target: " + target);
      }
      data_files.push_back({target, ReadFile(path)});
    }

    auto unsigned_package = symbian::sis::BuildApplicationPackageWithFiles(
        executable, assets, data_files, options);
    if (!unsigned_package.ok()) {
      throw std::runtime_error("SIS build failed: " +
                               std::string(unsigned_package.status().message()));
    }
    auto unsigned_info = symbian::sis::InspectPackage(*unsigned_package);
    if (!unsigned_info.ok() || unsigned_info->signed_package ||
        unsigned_info->files.size() != 3 + data_files.size()) {
      throw std::runtime_error("unsigned SIS validation failed");
    }

    if (Has(args, "--unsigned-output")) {
      WriteFile(Required(args, "--unsigned-output"), *unsigned_package);
    }
    const std::string certificate = ReadFile(Required(args, "--certificate"));
    const std::string private_key = ReadFile(Required(args, "--private-key"));
    auto signed_package = symbian::sis::SignPackage(
        *unsigned_package, certificate, private_key);
    if (!signed_package.ok()) {
      throw std::runtime_error("SIS signing failed: " +
                               std::string(signed_package.status().message()));
    }
    auto signed_info = symbian::sis::InspectPackage(*signed_package);
    if (!signed_info.ok() || !signed_info->signed_package ||
        signed_info->files.size() != unsigned_info->files.size() ||
        signed_info->options.uid != options.uid ||
        signed_info->options.executable_name != options.executable_name ||
        signed_info->options.version != options.version) {
      throw std::runtime_error("signed SIS validation failed");
    }
    WriteFile(Required(args, "--output"), *signed_package);
    std::cout << "writer=stage3-mda-openfile-v1\n";
    std::cout << "upstream_commit=b105f752f84d1b5640052f64c4a703017810d7b8\n";
    std::cout << "signed=true\n";
    std::cout << "registered=" << (signed_info->application_registered ? "true" : "false") << "\n";
    std::cout << "package_uid=" << signed_info->options.uid << "\n";
    std::cout << "package_name=" << signed_info->options.name << "\n";
    std::cout << "vendor=" << signed_info->options.vendor << "\n";
    std::cout << "executable_name=" << signed_info->options.executable_name << "\n";
    std::cout << "executable_uid=" << signed_info->executable_uid << "\n";
    std::cout << "executable_sha1=" << signed_info->executable_sha1 << "\n";
    std::cout << "e32_uid3=" << executable_info->uid3 << "\n";
    std::cout << "e32_imports=" << all_imports.str() << "\n";
    std::cout << "mda_import_ordinals=" << mda_imports.str() << "\n";
    std::cout << "midi_imports=" << midi_imports.str() << "\n";
    std::cout << "version=" << signed_info->options.version[0] << '.'
              << signed_info->options.version[1] << '.'
              << signed_info->options.version[2] << "\n";
    std::cout << "files=" << signed_info->files.size() << "\n";
    for (const auto& file : signed_info->files) {
      std::cout << file.target << '\t' << file.size << '\t' << file.sha1 << '\n';
    }
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
