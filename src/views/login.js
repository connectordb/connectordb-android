import React, {
    AppRegistry,
    Component,
    StyleSheet,
    Text,
    View,
    ScrollView,
    TextInput,
    DrawerLayoutAndroid,
    Image
} from 'react-native';

import {Button, Card, Ripple, Checkbox} from 'react-native-material-design';

import {connect} from 'react-redux';

import LogoPicture from '../components/logopicture';
import GatherCheckbox from '../components/gathercheckbox';

const styles = StyleSheet.create({
    container: {
        padding: 10,
        backgroundColor: '#F5FCFF'
    },
    labeltext: {
        flex: 1,
        flexDirection: "row"
    },
    welcome: {
        fontSize: 20,
        textAlign: 'center',
        margin: 10
    },
    instructions: {
        textAlign: 'center',
        color: '#333333',
        marginBottom: 5
    },
});

const render = () => (
    <ScrollView>
        <View style={styles.container}>
            <LogoPicture width={100} height={100}/>
            <Text style={styles.welcome}>
                ConnectorDB
            </Text>
            <Card>

                <Card.Body>
                    <Text>Log In to ConnectorDB</Text>
                    <TextInput placeholder="Username" autoCorrect={false} keyboardType="email-address"></TextInput>
                    <TextInput placeholder="Password" autoCorrect={false} secureTextEntry={true}></TextInput>
                </Card.Body>
                <Card.Actions position="left">
                    <Button text="Login" onPress={() => console.log("I pressed a flat button")}/>
                </Card.Actions>
            </Card>

            <Card>

                <Card.Body>
                    <Text>Server:</Text>
                    <TextInput placeholder="Server" autoCorrect={false} keyboardType="email-address" value="https://connectordb.com"/>
                    <Text>Device Name:</Text>
                    <TextInput placeholder="Device" autoCorrect={false} value="phone"/>
                    <GatherCheckbox/>
                </Card.Body>
            </Card>

        </View>
    </ScrollView>
);

export default connect((state) => ({gather: state.gather}), (dispatch) => ({}))(render);
