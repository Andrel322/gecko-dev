/* global chai */

var expect = chai.expect;
var sharedActions = loop.shared.actions;

describe("loop.store.ActiveRoomStore", function () {
  "use strict";

  var sandbox, dispatcher;

  beforeEach(function() {
    sandbox = sinon.sandbox.create();
    dispatcher = new loop.Dispatcher();
  });

  afterEach(function() {
    sandbox.restore();
  });

  describe("#constructor", function() {
    it("should throw an error if the dispatcher is missing", function() {
      expect(function() {
        new loop.store.ActiveRoomStore({mozLoop: {}});
      }).to.Throw(/dispatcher/);
    });

    it("should throw an error if mozLoop is missing", function() {
      expect(function() {
        new loop.store.ActiveRoomStore({dispatcher: dispatcher});
      }).to.Throw(/mozLoop/);
    });
  });

  describe("#setupWindowData", function() {
    var store, fakeMozLoop, fakeToken, fakeRoomName;

    beforeEach(function() {
      fakeToken = "337-ff-54";
      fakeRoomName = "Monkeys";
      fakeMozLoop = {
        rooms: { get: sandbox.stub() }
      };

      store = new loop.store.ActiveRoomStore(
        {mozLoop: fakeMozLoop, dispatcher: dispatcher});
      fakeMozLoop.rooms.get.
        withArgs(fakeToken).
        callsArgOnWith(1, // index of callback argument
        store, // |this| to call it on
        null, // args to call the callback with...
        {roomName: fakeRoomName}
      );
    });

    it("should trigger a change event", function(done) {
      store.on("change", function() {
        done();
      });

      dispatcher.dispatch(new sharedActions.SetupWindowData({
        windowId: "42",
        type: "room",
        roomToken: fakeToken
      }));
    });

    it("should set roomToken on the store from the action data",
      function(done) {

        store.once("change", function () {
          expect(store.getStoreState()).
            to.have.property('roomToken', fakeToken);
          done();
        });

        dispatcher.dispatch(new sharedActions.SetupWindowData({
          windowId: "42",
          type: "room",
          roomToken: fakeToken
        }));
      });

    it("should set serverData.roomName from the getRoomData callback",
      function(done) {

        store.once("change", function () {
          expect(store.getStoreState()).to.have.deep.property(
            'serverData.roomName', fakeRoomName);
          done();
        });

        dispatcher.dispatch(new sharedActions.SetupWindowData({
          windowId: "42",
          type: "room",
          roomToken: fakeToken
        }));
      });

    it("should set error on the store when getRoomData calls back an error",
      function(done) {

        var fakeError = new Error("fake error");
        fakeMozLoop.rooms.get.
          withArgs(fakeToken).
          callsArgOnWith(1, // index of callback argument
          store, // |this| to call it on
          fakeError); // args to call the callback with...

        store.once("change", function() {
          expect(this.getStoreState()).to.have.property('error', fakeError);
          done();
        });

        dispatcher.dispatch(new sharedActions.SetupWindowData({
          windowId: "42",
          type: "room",
          roomToken: fakeToken
        }));
      });

  });
});
