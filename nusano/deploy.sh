#!/usr/bin/env bash
#
# Copyright (c) 2019 Cosylab d.d.
# This software is distributed under the terms found
# in file LICENSE.txt that is included with this distribution.
#
TOP=$(pwd)

##############################################################
# Untar phoebus
##############################################################
cd $TOP
tar -xzf phoebus.tar.gz
rm -f phoebus.tar.gz

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
sudo chown root:root phoebus && sudo chmod 755 $_ && sudo ln -sfn $(pwd)/$_ /usr/local/bin/$_
sudo chown root:root phoebus.desktop && sudo chmod 644 $_ && sudo ln -sfn $(pwd)/$_ /usr/share/applications/$_
