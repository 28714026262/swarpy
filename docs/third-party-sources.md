# Third-party components in the Windows distribution

The application combines independent components. Their original notices are retained.

| Component | Version | Notices and corresponding source |
| --- | --- | --- |
| Eclipse Temurin / OpenJDK | 17.0.21.1+2 | runtime/NOTICE, runtime/legal/; the Release also provides OpenJDK17U-jdk-sources_17.0.21.1_2.tar.gz |
| NSIS installer stub | 3.13 | licenses/NSIS-COPYING.txt; [source release](https://sourceforge.net/projects/nsis/files/NSIS%203/3.13/) |
| React, Ant Design, Moment, JSZip | Pinned local frontend | licenses/frontend/ |
| Java dependencies, including SQLite JDBC | See JAR filenames | licenses/dependencies/; original META-INF notices also remain inside swapy.jar |
| jieba, WordNet, LemmInflect vocabulary sources | Pinned corpus revisions | licenses/library/; notices also travel in library/swapy-basic.csv |

The Temurin runtime is redistributed with its original legal and configuration files.
This Windows distribution does not include LibreOffice or install a separate
Visual C++ Redistributable. Optional office conversion is outside this package.

build.json records the application commit, library hash and downloaded component
hashes. files.json lists the distribution files and SHA256 values. Application
source and build scripts are available at the tagged GitHub release.
