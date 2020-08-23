#!/usr/bin/env bash
#
# Copyright (c) 2019 Cosylab d.d.
# This software is distributed under the terms found
# in file LICENSE.txt that is included with this distribution.
#
TOP=$(pwd)
INSTALL_PATH=/opt/nusano/phoebus

##############################################################
# Untar phoebus
##############################################################
cd $TOP
unzip -q phoebus-*.zip
rm -f phoebus-*.zip
tar xzvf phoebus_*.tar.gz
mv -f product-*/* .
rmdir product-*/

##############################################################
# Set owner
##############################################################
cd $TOP
sudo chown -R epics:epics *

##############################################################
# Set system files
##############################################################
cd $TOP
# Set owner, proper permissions and symbolic link
sudo chown root:root phoebus && sudo chmod 755 $_ && sudo ln -sfn $INSTALL_PATH/$_ /usr/local/bin/$_
sudo chown root:root phoebus.desktop && sudo chmod 644 $_ && sudo ln -sfn $INSTALL_PATH/$_ /usr/share/applications/$_
