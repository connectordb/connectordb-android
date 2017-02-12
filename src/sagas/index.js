
import basicSaga from './basic';
import connectordbSaga from './connectordb';
import loginSaga from './login';

export default function* sagas() {
    yield [
        basicSaga(),
        connectordbSaga(),
        loginSaga()
    ];
}