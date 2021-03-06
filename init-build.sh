echo "Prebuilding various dependencies needed for dronehub"

# The akka build will fail on some unimportant parts
# set -e
git submodule update --recursive --init

# echo "Installing LogAnalyzer dependencies"
# pip install numpy

if [ ! -f ~/nestor.conf ]; then
    echo "Seeding ~/nestor.conf YOU MUST EDIT IT LATER!"
    cp nestor.conf.template ~/nestor.conf
fi


# We build in /tmp because it might be a ramfs and much faster
echo rebuilding dependencies
rm -rf /tmp/dependencies
mkdir /tmp/dependencies
cd /tmp/dependencies

#SCALA=scala-2.10.4
#wget http://www.scala-lang.org/files/archive/$SCALA.tgz
#tar xvzf $SCALA.tgz
#pushd ~/bin
#ln -s ../dependencies/$SCALA/bin/* .
#popd

# We are not using hull currently, so deprecate for now
# git clone https://github.com/geeksville/hull-java.git
# cd hull-java/
# mvn install
# cd ..

# We want to wait for all of our spawned children before exiting
pids = ""

bash << EOF &
git clone https://github.com/geeksville/sbt-scalabuff.git
cd sbt-scalabuff/
sbt publishLocal
cd ..
EOF
pids="$pids $!"

bash << EOF &
git clone -b fixes_for_dronehub https://github.com/geeksville/json4s.git
cd json4s
sbt publishLocal
cd ..
EOF
pids="$pids $!"

bash << EOF &
# akka needs sphinx to make docs
# DO NOT USE SUDO it breaks the CI server
pip install sphinx
git clone https://github.com/geeksville/akka.git
cd akka
sbt -Dakka.scaladoc.diagrams=false publishLocal
cd ..

git clone -b 2.3.x_2.10 https://github.com/geeksville/scalatra.git
cd scalatra
sbt publishLocal
cd ..
EOF
pids="$pids $!"

bash << EOF &&
git clone https://github.com/geeksville/scala-activerecord.git
cd scala-activerecord
sbt "project core" publishLocal "project generator" publishLocal "project scalatra" publishLocal "project scalatraSbt" publishLocal
cd ..
EOF
pids="$pids $!"

echo Fixing up bad ivy files on codeship
find ~/.ivy2/cache -name \*.original | xargs rm

wait $pids # wait for our various parallel child jobs to finish
